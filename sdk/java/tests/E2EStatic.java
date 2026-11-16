package qentimart.e2e;

import com.sun.net.httpserver.HttpServer;
import qentimart.QentimArtClient;
import qentimart.QentimArtException;
import qentimart.StorageReport;
import qentimart.SyncOptions;
import qentimart.SyncResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static qentimart.e2e.E2EUtil.MiB;
import static qentimart.e2e.E2EUtil.deleteTree;
import static qentimart.e2e.E2EUtil.loadTree;
import static qentimart.e2e.E2EUtil.mib;
import static qentimart.e2e.E2EUtil.tree;

/**
 * tests/e2e.mjs 的 Java 静态模式部分。自己用 com.sun.net.httpserver 托管静态树目录，
 * 再以 baseUrl 做同步，不依赖调用方的进程。
 *
 * 用法： E2EStatic SITE_DIR DEST tree1.json tree2.json DELTA_BYTES
 */
public class E2EStatic {
    static int failures = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + msg);
        if (!cond) failures++;
    }

    public static void main(String[] args) throws Exception {
        String site = args[0], dest = args[1];
        Map<String, String> tree1 = loadTree(args[2]);
        Map<String, String> tree2 = loadTree(args[3]);
        long delta = Long.parseLong(args[4]);
        deleteTree(Paths.get(dest));
        Path siteRoot = Paths.get(site).toAbsolutePath().normalize();

        HttpServer httpd = serveDir(siteRoot);
        String baseUrl = "http://127.0.0.1:" + httpd.getAddress().getPort();

        try (QentimArtClient c = QentimArtClient.staticClient(baseUrl, null, 16)) {
            SyncResult r = c.sync(dest, new SyncOptions().version("1.0.0"));
            System.out.printf("  静态全新同步 1.0.0: %s in %.2fs = %s/s%n",
                    mib(r.bytes.downloadedWire), r.elapsedMs / 1000.0, mib(r.bytesPerSecond));
            check(tree(Paths.get(dest)).equals(tree1), "静态模式全新同步逐字节一致 (1.0.0)");
            StorageReport rep = QentimArtClient.storageReport(dest);
            check(rep.ratio() <= 1.10, String.format("静态模式 ratio %.3f%% <= 110%%", rep.ratio() * 100));

            r = c.sync(dest);
            check(r.version.equals("1.1.0") && tree(Paths.get(dest)).equals(tree2), "静态模式增量更新逐字节一致 (1.1.0)");
            check(r.bytes.downloadedRaw <= delta + MiB,
                    String.format("静态模式只下载差异 (%s <= %s + 1MiB)", mib(r.bytes.downloadedRaw), mib(delta)));
            check(r.bytes.reusedLocal > 0, "静态模式复用本地已有的块");

            r = c.sync(dest);
            check(r.upToDate && r.bytes.downloadedWire == 0, "静态模式第二次同步为空操作");

            Map<String, Object> d = c.diff("1.0.0", "1.1.0");
            long dl = ((Number) d.get("downloadBytes")).longValue();
            int chg = ((List<?>) d.get("added")).size() + ((List<?>) d.get("modified")).size();
            check(dl > 0 && chg > 0, "静态模式客户端 diff 可用");

            // 消费端路径安全：清单路径合法，但本地父目录是指向外部的符号链接 → 必须拒绝，不逃逸。
            Path evil = siteRoot.getParent().resolve("java-evil-symlink");
            E2EUtil.deleteTree(evil);
            craftEvilTree(evil, "sub/empty.txt");
            HttpServer esrv = serveDir(evil);
            try {
                Path edst = Paths.get(dest + "-escape");
                Path out = Paths.get(dest + "-escape-out");
                E2EUtil.deleteTree(edst);
                E2EUtil.deleteTree(out);
                Files.createDirectories(edst);
                Files.createDirectories(out);
                boolean symlinked = true;
                try {
                    Files.createSymbolicLink(edst.resolve("sub"), out);
                } catch (Exception e) {
                    symlinked = false;
                }
                if (!symlinked) {
                    System.out.println("  skip  符号链接父目录逃逸（本机无权限创建符号链接）");
                } else {
                    boolean threw = false;
                    String eurl = "http://127.0.0.1:" + esrv.getAddress().getPort();
                    try (QentimArtClient ec = QentimArtClient.staticClient(eurl)) {
                        ec.sync(edst.toString());
                    } catch (QentimArtException e) {
                        threw = "EUNSAFE".equals(e.code)
                                || (e.getMessage() != null && e.getMessage().matches("(?s).*(symlink|unsafe).*"));
                    }
                    boolean outEmpty;
                    try (Stream<Path> st = Files.list(out)) {
                        outEmpty = st.findAny().isEmpty();
                    }
                    check(threw && outEmpty, "符号链接父目录不会让写入逃逸到 root 外");
                }
            } finally {
                esrv.stop(0);
            }
        } finally {
            httpd.stop(0);
        }

        System.exit(failures > 0 ? 1 : 0);
    }

    static HttpServer serveDir(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/", ex -> {
            try {
                String rel = ex.getRequestURI().getPath().replaceAll("^/+", "");
                Path fp = base.resolve(rel).normalize();
                if (!fp.startsWith(base) || !Files.isRegularFile(fp)) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                byte[] body = Files.readAllBytes(fp);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            } catch (IOException e) {
                ex.close();
            }
        });
        s.start();
        return s;
    }

    static void craftEvilTree(Path root, String filePath) throws Exception {
        String empty = E2EUtil.sha256Hex(new byte[0]);
        String meta = "{\"version\":\"1.0.0\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"prerelease\":false,"
                + "\"fileCount\":1,\"totalSize\":0,\"chunkCount\":0,\"manifestSize\":0,\"baseVersion\":\"\",\"deltaBytes\":0}";
        String manifest = "{\"formatVersion\":1,\"version\":\"1.0.0\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"files\":[{\"path\":\"" + filePath + "\",\"size\":0,\"hash\":\"" + empty + "\"}]}";
        String index = "{\"formatVersion\":1,\"project\":\"evil\",\"updatedAt\":\"2026-01-01T00:00:00Z\","
                + "\"latest\":\"1.0.0\",\"latestPrerelease\":null,\"versions\":[" + meta + "]}";
        Files.createDirectories(root.resolve("versions/1.0.0"));
        Files.writeString(root.resolve("index.json"), index);
        Files.writeString(root.resolve("versions/1.0.0/meta.json"), meta);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(root.resolve("versions/1.0.0/manifest.json.gz"), bos.toByteArray());
    }
}
