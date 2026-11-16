package qentimart.e2e;

import qentimart.internal.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/** e2e 测试共用工具。 */
final class E2EUtil {
    static final long MiB = 1 << 20;

    private E2EUtil() {
    }

    static String mib(double n) {
        return String.format("%.1fMiB", n / MiB);
    }

    static String sha256Hex(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(b);
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte x : d) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }

    /** 递归计算目录下每个文件的 SHA-256（跳过 .qentimart）。 */
    static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) return out;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String r = root.relativize(p).toString().replace('\\', '/');
                if (r.equals(".qentimart") || r.startsWith(".qentimart/")) continue;
                out.put(r, sha256Hex(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> loadTree(String path) throws Exception {
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readAllBytes(Paths.get(path)));
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), (String) e.getValue());
        return out;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
