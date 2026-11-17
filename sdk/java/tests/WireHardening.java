package qentimart.e2e;

import qentimart.QentimArtException;
import qentimart.internal.Wire;

import java.util.Arrays;
import java.util.zip.Deflater;

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

    public static void main(String[] args) {
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
        };
        for (String p : bad) expectReject("拒绝不安全路径: " + p, () -> Wire.validatePath(p));

        // 合法路径应被接受（含非 ASCII）。
        for (String p : new String[]{"a.png", "tex/a.png", "dir/sub/file.bin", "名字.dat"}) {
            try {
                Wire.validatePath(p);
                check(true, "接受合法路径: " + p);
            } catch (QentimArtException e) {
                check(false, "合法路径被误拒: " + p + " -> " + e.getMessage());
            }
        }

        System.out.println(failures == 0 ? "WireHardening: ALL PASS" : ("WireHardening: " + failures + " FAILED"));
        if (failures != 0) System.exit(1);
    }
}
