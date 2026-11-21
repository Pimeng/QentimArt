package qentimart.internal;

import qentimart.Chunk;
import qentimart.Manifest;
import qentimart.ManifestFile;
import qentimart.QentimArtException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** 块帧流、静态 blob、manifest 解析与路径校验（参见 docs/PROTOCOL.md）。 */
public final class Wire {
    private Wire() {
    }

    public static final String STATE_DIR = ".qentimart";
    public static final int FRAME_HEADER_LEN = 41;
    public static final int ENC_RAW = 0;
    public static final int ENC_DEFLATE = 1;
    public static final int ENC_MISSING = 0xFF;

    // 以下大小上限用于约束“来自远端（服务端 / 公开静态源）”的数据体积，
    // 防止被攻陷或恶意的来源用超大 / 解压炸弹响应耗尽客户端内存。
    /** 单个块原始大小上限（与服务端 wire.MaxChunkSize 保持一致）。 */
    public static final int MAX_CHUNK_SIZE = 16 << 20;
    /** 静态 blob 对象下载上限（blob 头 + 最大块 + deflate 余量）。 */
    public static final int MAX_BLOB_SIZE = 5 + MAX_CHUNK_SIZE + 1024;
    /** 压缩 manifest 下载上限（与服务端上传上限 maxManifestUpload 一致）。 */
    public static final int MAX_MANIFEST_GZ = 256 << 20;
    /** 解压后 manifest 大小上限（与服务端 maxManifestBytes 一致）。 */
    public static final long MAX_MANIFEST_RAW = 1L << 30;
    /** JSON 响应（版本目录 / index.json）下载上限。 */
    public static final int MAX_JSON = 64 << 20;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9-]++(?:\\.[a-zA-Z0-9-]++)*+)?");
    private static final Pattern DEVICE = Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]) *");
    private static final char[] HEXD = "0123456789abcdef".toCharArray();
    private static final String EMPTY_HASH = sha256Hex(new byte[0]);

    /** 帧回调。 */
    public interface FrameSink {
        void frame(String hashHex, int enc, long rawLen, byte[] payload) throws Exception;
    }

    /** 从帧流逐帧回调；在帧边界遇到干净 EOF 则正常结束，中途截断抛 ETRUNCATED。 */
    public static void readFrames(InputStream in, FrameSink sink) throws Exception {
        DataInputStream d = new DataInputStream(
                in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 1 << 16));
        byte[] hdr = new byte[FRAME_HEADER_LEN];
        while (true) {
            int first = d.read();
            if (first < 0) return; // 干净 EOF
            hdr[0] = (byte) first;
            try {
                d.readFully(hdr, 1, FRAME_HEADER_LEN - 1);
            } catch (EOFException e) {
                throw new QentimArtException("truncated chunk stream", -1, "ETRUNCATED");
            }
            String hashHex = hex(hdr, 0, 32);
            int enc = hdr[32] & 0xFF;
            long rawLen = u32(hdr, 33);
            long stored = u32(hdr, 37);
            // 在按 stored 分配内存前先校验上限，防止恶意服务端用超大帧头耗尽内存。
            if (stored > MAX_CHUNK_SIZE + 1024 || (enc != ENC_MISSING && rawLen > MAX_CHUNK_SIZE)) {
                throw new QentimArtException("frame too large (" + rawLen + "/" + stored + ")", -1, "ECORRUPT");
            }
            byte[] payload = new byte[(int) stored];
            try {
                d.readFully(payload);
            } catch (EOFException e) {
                throw new QentimArtException("truncated chunk stream", -1, "ETRUNCATED");
            }
            sink.frame(hashHex, enc, rawLen, payload);
        }
    }

    /** 解码一个帧 payload（根据 enc 可能需要 inflate），并校验长度与 SHA-256。 */
    public static byte[] decodeChunk(String hashHex, int enc, long rawLen, byte[] payload) {
        if (enc != ENC_MISSING && (rawLen < 0 || rawLen > MAX_CHUNK_SIZE)) {
            throw new QentimArtException("chunk " + hashHex + ": declared length " + rawLen + " exceeds limit", -1, "ECORRUPT");
        }
        byte[] raw;
        if (enc == ENC_RAW) {
            raw = payload;
        } else if (enc == ENC_DEFLATE) {
            raw = inflateRaw(hashHex, payload, (int) rawLen);
        } else if (enc == ENC_MISSING) {
            throw new QentimArtException("chunk " + hashHex + " is missing on the server", -1, "EMISSING");
        } else {
            throw new QentimArtException("unknown chunk encoding " + enc);
        }
        if (raw.length != rawLen || !sha256Hex(raw).equals(hashHex)) {
            throw new QentimArtException("chunk " + hashHex + " failed verification", -1, "ECORRUPT");
        }
        return raw;
    }

    /** 解码一个静态 blob 对象（enc u8 | rawLen u32be | payload），并校验哈希。 */
    public static byte[] decodeBlob(String hashHex, byte[] buf) {
        if (buf.length < 5) {
            throw new QentimArtException("chunk " + hashHex + ": blob too short", -1, "ECORRUPT");
        }
        int enc = buf[0] & 0xFF;
        long rawLen = u32(buf, 1);
        return decodeChunk(hashHex, enc, rawLen, Arrays.copyOfRange(buf, 5, buf.length));
    }

    // inflateRaw 以 rawLen（调用方已校验 <= MAX_CHUNK_SIZE）为硬上限进行解压：
    // 直接解压进固定大小的输出缓冲，若解压结果超出 rawLen 立即报错，
    // 从而防止 deflate 炸弹（极小 payload 膨胀成数 GB）耗尽客户端内存。
    private static byte[] inflateRaw(String hashHex, byte[] payload, int rawLen) {
        Inflater inf = new Inflater(true);
        inf.setInput(payload);
        byte[] out = new byte[rawLen];
        int off = 0;
        try {
            while (off < rawLen && !inf.finished()) {
                int n = inf.inflate(out, off, rawLen - off);
                if (n == 0) {
                    if (inf.finished() || inf.needsDictionary()) break;
                    if (inf.needsInput()) {
                        throw new QentimArtException("chunk " + hashHex + ": bad deflate data (truncated)", -1, "ECORRUPT");
                    }
                }
                off += n;
            }
            // 输出已填满 rawLen：若还能再产出字节，说明解压结果超过声明长度（炸弹 / 损坏）。
            if (off == rawLen && !inf.finished() && inf.inflate(new byte[1]) > 0) {
                throw new QentimArtException("chunk " + hashHex + ": inflated data exceeds declared length " + rawLen, -1, "ECORRUPT");
            }
        } catch (DataFormatException e) {
            throw new QentimArtException("chunk " + hashHex + ": bad deflate data: " + e.getMessage(), -1, "ECORRUPT");
        } finally {
            inf.end();
        }
        // 产出不足时返回已得部分，交由上层的长度 / 哈希校验报 ECORRUPT。
        return off == rawLen ? out : Arrays.copyOf(out, off);
    }

    public static Manifest parseManifest(byte[] gz) {
        if (gz.length > MAX_MANIFEST_GZ) throw new QentimArtException("compressed manifest exceeds size limit", -1, "ETOOLARGE");
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(gunzip(gz, MAX_MANIFEST_RAW));
        } catch (Exception e) {
            throw new QentimArtException("invalid manifest: " + e.getMessage(), e);
        }
        if (doc == null) throw new QentimArtException("invalid manifest");
        Object fv = doc.get("formatVersion");
        if (!(fv instanceof Long) || (Long) fv != 1L) {
            throw new QentimArtException("unsupported manifest formatVersion " + fv);
        }
        String version = validateVersion(doc.get("version"));
        if (!(doc.get("files") instanceof List<?> entries)) throw new QentimArtException("manifest: expected files array");
        LinkedHashMap<String, ManifestFile> files = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        Map<String, Long> chunkSizes = new HashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> f) || !(f.get("path") instanceof String path)
                    || !(f.get("size") instanceof Long size) || size < 0) {
                throw new QentimArtException("manifest: invalid file entry");
            }
            validatePath(path);
            String key = path.toLowerCase(Locale.ROOT);
            if (!paths.add(key) || dirs.contains(key)) throw new QentimArtException("manifest: conflicting path " + path);
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                String parent = key.substring(0, slash);
                if (paths.contains(parent)) throw new QentimArtException("manifest: file used as directory in " + path);
                dirs.add(parent);
            }

            String hash = manifestHash(f.get("hash"));
            List<Chunk> chunks = new ArrayList<>();
            Object refs = f.get("chunks");
            if (refs != null && !(refs instanceof List)) throw new QentimArtException("manifest: invalid chunks for " + path);
            if (refs instanceof List<?> pairs && !pairs.isEmpty()) {
                long off = 0;
                for (Object ref : pairs) {
                    if (!(ref instanceof List<?> pair) || pair.size() != 2 || !(pair.get(1) instanceof Long n)
                            || n <= 0 || n > MAX_CHUNK_SIZE || n > size - off) {
                        throw new QentimArtException("manifest: invalid chunk reference in " + path);
                    }
                    chunks.add(new Chunk(manifestHash(pair.get(0)), n, off));
                    off += n;
                }
                if (off != size) throw new QentimArtException("manifest: chunk sizes of " + path + " do not add up");
            } else if (size > 0 && size <= MAX_CHUNK_SIZE) {
                chunks.add(new Chunk(hash, size, 0));
            } else if (size != 0 || !hash.equals(EMPTY_HASH)) {
                throw new QentimArtException("manifest: invalid implied chunk for " + path);
            }
            if (chunks.size() == 1 && !chunks.get(0).hash().equals(hash)) {
                throw new QentimArtException("manifest: file and single chunk hashes differ for " + path);
            }
            for (Chunk chunk : chunks) {
                Long previous = chunkSizes.putIfAbsent(chunk.hash(), chunk.size());
                if (previous != null && previous != chunk.size()) throw new QentimArtException("manifest: conflicting chunk sizes");
            }
            files.put(path, new ManifestFile(path, size, hash, chunks));
        }
        return new Manifest(version, str(doc.get("createdAt")), files);
    }

    private static String manifestHash(Object value) {
        if (!(value instanceof String s) || !HASH.matcher(s).matches()) {
            throw new QentimArtException("manifest: invalid SHA-256 digest");
        }
        return s;
    }

    /** A concrete version from the catalog must be a single portable URL path component. */
    public static String validateVersion(Object value) {
        if (!(value instanceof String s) || !VERSION.matcher(s).matches()) {
            throw new QentimArtException("invalid version in response");
        }
        return s;
    }

    /** 校验路径为可移植的相对路径（规则与服务端 manifest.ValidatePath 保持一致）。 */
    public static void validatePath(String p) {
        if (p == null || p.isEmpty() || p.length() > 1024 || p.getBytes(StandardCharsets.UTF_8).length > 1024
                || p.codePoints().anyMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
            throw new QentimArtException("unsafe path " + p);
        }
        String[] segs = p.split("/", -1);
        if (segs.length > 0 && segs[0].toLowerCase(Locale.ROOT).equals(STATE_DIR)) {
            throw new QentimArtException("path uses reserved directory " + STATE_DIR + ": " + p);
        }
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                throw new QentimArtException("unsafe path " + p);
            }
            // 段尾 '.' / ' ' 在 Windows 上不可移植（会被静默去除）。
            if (s.endsWith(".") || s.endsWith(" ")) {
                throw new QentimArtException("path not portable to Windows (segment ends with '.' or ' '): " + p);
            }
            int dot = s.indexOf('.');
            String stem = dot < 0 ? s : s.substring(0, dot);
            if (DEVICE.matcher(stem).matches()) throw new QentimArtException("reserved device name in path " + p);
            // 控制字符与 Windows 保留字符（含 '\\' 与盘符 ':'）。
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || "\\<>:\"|?*".indexOf(c) >= 0) {
                    throw new QentimArtException("path contains a character not portable to Windows: " + p);
                }
            }
        }
    }

    /** 在客户端比较两份 manifest（静态模式没有服务端 /diff 接口时使用）。 */
    public static Map<String, Object> diffManifests(Manifest a, Manifest b) {
        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (ManifestFile f : b.files.values()) {
            ManifestFile o = a.files.get(f.path());
            if (o == null) added.add(f.path());
            else if (!o.hash().equals(f.hash())) modified.add(f.path());
        }
        for (String p : a.files.keySet()) {
            if (!b.files.containsKey(p)) removed.add(p);
        }
        Set<String> have = new HashSet<>();
        for (ManifestFile f : a.files.values()) {
            for (Chunk c : f.chunks()) have.add(c.hash());
        }
        long download = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        for (ManifestFile f : b.files.values()) {
            total += f.size();
            for (Chunk c : f.chunks()) {
                if (!seen.add(c.hash())) continue;
                if (!have.contains(c.hash())) download += c.size();
            }
        }
        Collections.sort(added);
        Collections.sort(modified);
        Collections.sort(removed);
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("from", a.version);
        r.put("to", b.version);
        r.put("added", added);
        r.put("modified", modified);
        r.put("removed", removed);
        r.put("downloadBytes", download);
        r.put("totalBytes", total);
        return r;
    }

    // ------------------------------------------------------------ 工具

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    public static String hex(byte[] b, int off, int len) {
        char[] c = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            c[i * 2] = HEXD[v >>> 4];
            c[i * 2 + 1] = HEXD[v & 0xF];
        }
        return new String(c);
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    public static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    public static String sha256Hex(byte[] data) {
        return hex(sha256().digest(data));
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new QentimArtException("SHA-256 unavailable", e);
        }
    }

    public static byte[] gunzip(byte[] gz, long max) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return readCapped(gis, max, "manifest");
        }
    }

    /**
     * 按响应的 Content-Encoding 透明解压：HttpClient 不会自动处理传输层编码，
     * 而很多 CDN / 对象存储会对 index.json 之类的响应强制 gzip（即便请求 identity）。
     * 以响应头为准解码，对不带该头的 .gz 清单和原始 chunk 零影响；解压结果同样受
     * max 上限约束，防止被压缩炸弹耗尽内存。
     */
    public static byte[] decodeBody(byte[] data, String encoding, long max) {
        String enc = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
        try {
            switch (enc) {
                case "":
                case "identity":
                    return data;
                case "gzip":
                case "x-gzip":
                    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
                        return readCapped(gis, max, "response body");
                    }
                case "deflate":
                    return inflateBody(data, max);
                case "br":
                    throw new QentimArtException("response is brotli-encoded but Java has no built-in brotli decoder", -1, "EUNSUPPORTED");
                default:
                    return data; // 未知编码：原样返回，交由上层解析报错
            }
        } catch (IOException e) {
            throw new QentimArtException("cannot decode response body: " + e.getMessage(), -1, "EDECODE");
        }
    }

    private static byte[] inflateBody(byte[] data, long max) throws IOException {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data))) {
            return readCapped(iis, max, "response body"); // zlib 包装（有头）
        } catch (IOException e) {
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data), new Inflater(true))) {
                return readCapped(iis, max, "response body"); // 裸 deflate（无头）
            }
        }
    }

    /** 从流中最多读取 max 字节，超过即报错，防止恶意来源用超大响应体耗尽客户端内存。 */
    public static byte[] readCapped(InputStream in, long max, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) {
                throw new QentimArtException(what + " exceeds size limit of " + max + " bytes", -1, "ETOOLARGE");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
