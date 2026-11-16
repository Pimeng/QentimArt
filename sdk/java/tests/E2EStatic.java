package qentimart.e2e;

import com.sun.net.httpserver.HttpServer;
import qentimart.QentimArtClient;
import qentimart.StorageReport;
import qentimart.SyncOptions;
import qentimart.SyncResult;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

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

        HttpServer httpd = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpd.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        httpd.createContext("/", ex -> {
            try {
                String rel = ex.getRequestURI().getPath().replaceAll("^/+", "");
                Path fp = siteRoot.resolve(rel).normalize();
                if (!fp.startsWith(siteRoot) || !Files.isRegularFile(fp)) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                byte[] body = Files.readAllBytes(fp);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception e) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
            }
        });
        httpd.start();
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
        } finally {
            httpd.stop(0);
        }

        System.exit(failures > 0 ? 1 : 0);
    }
}
