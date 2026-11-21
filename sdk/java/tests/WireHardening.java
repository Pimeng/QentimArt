package qentimart.e2e;

import qentimart.QentimArtException;
import qentimart.internal.Wire;
import qentimart.internal.HttpTransport;
import qentimart.internal.Json;
import qentimart.internal.State;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Wire 加固逻辑的纯单元测试（无需服务端）：解压上限（deflate 炸弹防护）、
 * rawLen 上限、validatePath 与服务端对齐。由 tests/e2e.mjs 直接运行。
 */
public class WireHardening {
    static int failures = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + msg);
        if (!cond) failures++;
    }

    static void expectReject(String msg, Runnable r) {
        try {
            r.run();
            check(false, msg + "（未报错）");
        } catch (QentimArtException e) {
            check(true, msg);
        }
    }

    // 裸 DEFLATE（nowrap=true），与 Wire.ENC_DEFLATE 的解码方式一致。
    static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_SPEED, true);
        d.setInput(raw);
        d.finish();
        byte[] out = new byte[Math.max(64, raw.length)];
        int total = 0;
        while (!d.finished()) {
            if (total == out.length) out = Arrays.copyOf(out, out.length * 2);
            total += d.deflate(out, total, out.length - total);
        }
        d.end();
        return Arrays.copyOf(out, total);
    }

    static byte[] blob(int enc, long rawLen, byte[] payload) {
        byte[] b = new byte[5 + payload.length];
        b[0] = (byte) enc;
        b[1] = (byte) (rawLen >>> 24);
        b[2] = (byte) (rawLen >>> 16);
        b[3] = (byte) (rawLen >>> 8);
        b[4] = (byte) rawLen;
        System.arraycopy(payload, 0, b, 5, payload.length);
        return b;
    }

    static byte[] manifest(Object files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(Json.writeBytes(Map.of("formatVersion", 1, "version", "1.0.0", "files", files), false));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void checkState() throws IOException {
        Path root = Files.createTempDirectory("qentimart-state-");
        Path dir = root.resolve(Wire.STATE_DIR);
        Path lock = dir.resolve(State.LOCK_FILE);
        Path state = dir.resolve(State.STATE_FILE);
        Path cachedManifest = dir.resolve(State.MANIFEST_FILE);
        try {
            Files.createDirectory(dir);
            Files.write(cachedManifest, manifest(List.of()));
            Files.writeString(state, "{\"formatVersion\":1,\"version\":\"1.0.0\",\"files\":[]}");
            check(State.read(root).manifest == null, "损坏的本地状态不会启用缓存清单");

            Files.createFile(lock);
            boolean locked = false;
            try {
                State.acquireLock(root).run();
            } catch (QentimArtException e) {
                locked = "ELOCKED".equals(e.code);
            }
            check(locked, "保留仍可能在写入的新锁");
            Files.setLastModifiedTime(lock, FileTime.fromMillis(System.currentTimeMillis() - 61_000));
            State.acquireLock(root).run();
            check(!Files.exists(lock), "恢复中断遗留的旧锁");
        } finally {
            Files.deleteIfExists(lock);
            Files.deleteIfExists(state);
            Files.deleteIfExists(cachedManifest);
            Files.deleteIfExists(dir);
            Files.deleteIfExists(root);
        }
    }

    public static void main(String[] args) throws IOException {
        String zero = "0".repeat(64); // 合法形状的占位哈希

        // 1) 合法 deflate 块可正常解码并通过哈希校验。
        byte[] raw = "hello qentimart".getBytes();
        byte[] decoded = Wire.decodeBlob(Wire.sha256Hex(raw), blob(Wire.ENC_DEFLATE, raw.length, deflate(raw)));
        check(Arrays.equals(decoded, raw), "合法 deflate 块解码正确");

        // 2) deflate 炸弹：极小 payload 解压结果远大于声明 rawLen → 必须拒绝。
        byte[] bomb = new byte[64 << 20]; // 64 MiB 全零
        byte[] zbomb = deflate(bomb);
        check(bomb.length / zbomb.length >= 50,
                "64MiB 全零压缩放大比 >=50x（压缩后 " + zbomb.length + "B，约 " + (bomb.length / zbomb.length) + "x）");
        expectReject("声明 rawLen=1024 但解压远超上限 → 拒绝",
                () -> Wire.decodeBlob(zero, blob(Wire.ENC_DEFLATE, 1024, zbomb)));

        // 3) rawLen 超过 MAX_CHUNK_SIZE → 在分配前拒绝。
        expectReject("rawLen 超过 16MiB 上限 → 拒绝",
                () -> Wire.decodeChunk(zero, Wire.ENC_DEFLATE, (long) Wire.MAX_CHUNK_SIZE + 1, new byte[8]));

        // 4) validatePath 与服务端对齐：拒绝不可移植 / 越界路径。
        String[] bad = {
                "a/../b", "../x", "/abs", "a//b", ".qentimart/x", ".QentimArt/y",
                "ctrl/\u0001x", "name.", "name ", "a/b<c", "q?x", "c:/x", "a\\b",
                "dir/LpT1.txt", "NUL", "conout$"
        };
        for (String p : bad) expectReject("拒绝不安全路径: " + p, () -> Wire.validatePath(p));
        expectReject("路径按 UTF-8 字节数限制", () -> Wire.validatePath("文".repeat(342)));

        // 合法路径应被接受（含非 ASCII）。
        for (String p : new String[]{"a.png", "tex/a.png", "dir/sub/file.bin", "名字.dat"}) {
            try {
                Wire.validatePath(p);
                check(true, "接受合法路径: " + p);
            } catch (QentimArtException e) {
                check(false, "合法路径被误拒: " + p + " -> " + e.getMessage());
            }
        }

        var entry = Map.of("path", "a", "size", 1, "hash", zero);
        check(Wire.parseManifest(manifest(List.of())).files.isEmpty(), "接受显式空发布");
        check(Wire.parseManifest(manifest(List.of(entry))).files.size() == 1, "接受单块清单");
        expectReject("files 必须为数组", () -> Wire.parseManifest(manifest(false)));
        expectReject("清单禁止相同路径", () -> Wire.parseManifest(manifest(List.of(entry, entry))));
        expectReject("清单禁止大小写文件/目录冲突", () -> Wire.parseManifest(manifest(List.of(
                entry, Map.of("path", "A/b", "size", 1, "hash", zero)))));
        expectReject("无 chunks 的文件也受块大小限制", () -> Wire.parseManifest(manifest(List.of(
                Map.of("path", "a", "size", Wire.MAX_CHUNK_SIZE + 1L, "hash", zero)))));
        expectReject("同一摘要的块长度必须一致", () -> Wire.parseManifest(manifest(List.of(entry,
                Map.of("path", "b", "size", 2, "hash", zero)))));
        expectReject("摘要必须是 SHA-256 十六进制", () -> Wire.parseManifest(manifest(List.of(
                Map.of("path", "a", "size", 1, "hash", "invalid")))));
        expectReject("静态索引必须提供单段版本号", () -> Wire.validateVersion("other/version"));
        check(HttpTransport.sourceKey("HTTPS://Example.COM:443/repo///?v=1").equals("https://example.com/repo"),
                "规范化源标识不保存查询参数");
        checkState();

        System.out.println(failures == 0 ? "WireHardening: ALL PASS" : ("WireHardening: " + failures + " FAILED"));
        if (failures != 0) System.exit(1);
    }
}
