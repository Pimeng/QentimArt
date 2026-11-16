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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/** 块帧流、静态 blob、manifest 解析与路径校验（参见 docs/PROTOCOL.md）。 */
public final class Wire {
    private Wire() {
    }

    public static final String STATE_DIR = ".qentimart";
    public static final int FRAME_HEADER_LEN = 41;
    public static final int ENC_RAW = 0;
    public static final int ENC_DEFLATE = 1;
    public static final int ENC_MISSING = 0xFF;

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

    private static byte[] inflateRaw(String hashHex, byte[] payload, int rawLenHint) {
        Inflater inf = new Inflater(true);
        inf.setInput(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(rawLenHint, 64));
        byte[] tmp = new byte[1 << 16];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(tmp);
                if (n == 0) {
                    if (inf.finished() || inf.needsDictionary()) break;
                    if (inf.needsInput()) {
                        throw new QentimArtException("chunk " + hashHex + ": bad deflate data (truncated)", -1, "ECORRUPT");
                    }
                }
                out.write(tmp, 0, n);
            }
        } catch (DataFormatException e) {
            throw new QentimArtException("chunk " + hashHex + ": bad deflate data: " + e.getMessage(), -1, "ECORRUPT");
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    public static Manifest parseManifest(byte[] gz) {
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(gunzip(gz));
        } catch (Exception e) {
            throw new QentimArtException("invalid manifest: " + e.getMessage(), e);
        }
        Object fv = doc.get("formatVersion");
        if (!(fv instanceof Long) || (Long) fv != 1L) {
            throw new QentimArtException("unsupported manifest formatVersion " + fv);
        }
        LinkedHashMap<String, ManifestFile> files = new LinkedHashMap<>();
        Object rawFiles = doc.get("files");
        if (rawFiles instanceof List) {
            for (Object o : (List<?>) rawFiles) {
                Map<?, ?> f = (Map<?, ?>) o;
                String path = (String) f.get("path");
                long size = ((Number) f.get("size")).longValue();
                String fh = (String) f.get("hash");
                validatePath(path);
                List<Chunk> chunks = new ArrayList<>();
                Object rc = f.get("chunks");
                if (rc instanceof List && !((List<?>) rc).isEmpty()) {
                    long off = 0;
                    for (Object c : (List<?>) rc) {
                        List<?> pair = (List<?>) c;
                        String h = (String) pair.get(0);
                        long sz = ((Number) pair.get(1)).longValue();
                        chunks.add(new Chunk(h, sz, off));
                        off += sz;
                    }
                    if (off != size) {
                        throw new QentimArtException("manifest: chunk sizes of " + path + " do not add up");
                    }
                } else if (size > 0) {
                    chunks.add(new Chunk(fh, size, 0));
                }
                files.put(path, new ManifestFile(path, size, fh, chunks));
            }
        }
        return new Manifest(str(doc.get("version")), str(doc.get("createdAt")), files);
    }

    public static void validatePath(String p) {
        if (p == null || p.isEmpty() || p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0) {
            throw new QentimArtException("unsafe path " + p);
        }
        String[] segs = p.split("/", -1);
        if (segs.length > 0 && segs[0].toLowerCase(Locale.ROOT).equals(STATE_DIR)) {
            throw new QentimArtException("unsafe path " + p);
        }
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || isDrive(s)) {
                throw new QentimArtException("unsafe path " + p);
            }
        }
    }

    private static boolean isDrive(String s) {
        return s.length() == 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':';
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

    private static final char[] HEXD = "0123456789abcdef".toCharArray();

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

    public static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return gis.readAllBytes();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
