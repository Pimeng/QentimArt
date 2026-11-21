package qentimart.e2e;

import qentimart.QentimArtClient;
import qentimart.QentimArtException;
import qentimart.StorageReport;
import qentimart.SyncConflictException;
import qentimart.SyncOptions;
import qentimart.SyncResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static qentimart.e2e.E2EUtil.MiB;
import static qentimart.e2e.E2EUtil.deleteTree;
import static qentimart.e2e.E2EUtil.loadTree;
import static qentimart.e2e.E2EUtil.mib;
import static qentimart.e2e.E2EUtil.tree;

/**
 * tests/e2e.mjs 的 Java 动态模式部分（服务端由该测试脚本启动）。
 *
 * 用法： E2EClient SERVER PROJECT TOKEN DEST tree1.json tree2.json DELTA_BYTES
 */
public class E2EClient {
    static int failures = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + msg);
        if (!cond) failures++;
    }

    public static void main(String[] args) throws Exception {
        String server = args[0], project = args[1], token = args[2], dest = args[3];
        Map<String, String> tree1 = loadTree(args[4]);
        Map<String, String> tree2 = loadTree(args[5]);
        long delta = Long.parseLong(args[6]);
        deleteTree(Paths.get(dest));

        try (QentimArtClient c = new QentimArtClient(server, project, token, 8)) {
            SyncResult r = c.sync(dest, new SyncOptions().version("1.0.0"));
            System.out.printf("  fresh sync 1.0.0: %s in %.2fs = %s/s%n",
                    mib(r.bytes.downloadedWire), r.elapsedMs / 1000.0, mib(r.bytesPerSecond));
            check(tree(Paths.get(dest)).equals(tree1), "fresh sync is byte-identical to 1.0.0");
            StorageReport rep = QentimArtClient.storageReport(dest);
            check(rep.ratio() <= 1.10, String.format("storage ratio after fresh sync %.3f%% <= 110%%", rep.ratio() * 100));

            r = c.sync(dest);
            check(r.version.equals("1.1.0"), "latest resolves to 1.1.0");
            check(tree(Paths.get(dest)).equals(tree2), "incremental update is byte-identical to 1.1.0");
            check(r.bytes.downloadedRaw <= delta + MiB,
                    String.format("update downloads only the delta (%s <= %s + 1MiB)", mib(r.bytes.downloadedRaw), mib(delta)));
            check(r.bytes.reusedLocal > 0, "unchanged chunks of modified/moved files are copied locally");
            rep = QentimArtClient.storageReport(dest);
            check(rep.ratio() <= 1.10, String.format("storage ratio after update %.3f%% <= 110%%", rep.ratio() * 100));

            r = c.sync(dest);
            check(r.upToDate && r.bytes.downloadedWire == 0, "second sync is a no-op");

            // 本地改动
            List<String> uiKeys = new ArrayList<>();
            for (String k : tree2.keySet()) if (k.startsWith("ui/")) uiKeys.add(k);
            Collections.sort(uiKeys);
            String victim = uiKeys.get(0);
            Files.writeString(Paths.get(dest).resolve(victim), "locally edited");
            Files.createDirectories(Paths.get(dest).resolve("ui"));
            Files.writeString(Paths.get(dest).resolve("ui/untracked-by-user.txt"), "mine");
            Map<String, Object> st = c.status(dest);
            check(((List<?>) st.get("modified")).contains(victim)
                            && ((List<?>) st.get("untracked")).contains("ui/untracked-by-user.txt"),
                    "status reports modified + untracked files");

            boolean threw = false;
            try {
                c.sync(dest, new SyncOptions().localChanges("fail"));
            } catch (SyncConflictException e) {
                threw = e.conflicts.size() == 2;
            }
            check(threw, "localChanges='fail' aborts with both conflicts");

            r = c.sync(dest, new SyncOptions().localChanges("keep"));
            boolean keptOk = Files.readString(Paths.get(dest).resolve(victim)).equals("locally edited");
            check(r.files.kept.size() == 2 && keptOk, "localChanges='keep' preserves local edits");

            c.sync(dest);
            check(Files.readString(Paths.get(dest).resolve("ui/untracked-by-user.txt")).equals("mine")
                            && tree2.get(victim).equals(tree(Paths.get(dest)).get(victim)),
                    "default overwrite restores managed files and preserves untracked files");
            c.sync(dest, new SyncOptions().deleteUntracked(true));
            check(tree(Paths.get(dest)).equals(tree2), "deleteUntracked removes extra files on an established sync");

            r = c.sync(dest, new SyncOptions().version("1.0.0"));
            check(tree(Paths.get(dest)).equals(tree1),
                    String.format("downgrade to 1.0.0 is byte-identical (downloaded %s, reused %s)",
                            mib(r.bytes.downloadedRaw), mib(r.bytes.reusedLocal)));

            // Node / Python / Java SDK 共享状态格式：status 必须看到一个干净的目录树。
            st = c.status(dest);
            check(Boolean.TRUE.equals(st.get("clean")) && "1.0.0".equals(st.get("version")),
                    "state is readable and clean after downgrade");

            String wrong = dest + "-wrongdir";
            deleteTree(Paths.get(wrong));
            Files.createDirectories(Paths.get(wrong).resolve("important"));
            Files.writeString(Paths.get(wrong).resolve("important/user-file.doc"), "do not delete");
            r = c.sync(wrong, new SyncOptions().version("1.0.0"));
            check(Files.exists(Paths.get(wrong).resolve("important/user-file.doc"))
                            && r.files.kept.contains("important/user-file.doc"),
                    "first sync never deletes unknown files (deleteUntrackedOnFirstSync=false)");

            Path shape = Paths.get(dest + "-shape");
            deleteTree(shape);
            Path protectedFile = shape.resolve(victim).resolve("local-note.txt");
            Files.createDirectories(protectedFile.getParent());
            Files.writeString(protectedFile, "local");
            boolean shapeConflict = false;
            try {
                c.plan(shape.toString(), new SyncOptions().version("1.1.0"));
            } catch (SyncConflictException expected) {
                shapeConflict = expected.conflicts.contains(victim);
            }
            check(shapeConflict && Files.readString(protectedFile).equals("local"),
                    "a local child blocks directory-to-file replacement during planning");
            SyncResult shapePlan = c.plan(shape.toString(), new SyncOptions().version("1.1.0")
                    .deleteUntracked(true).deleteUntrackedOnFirstSync(true));
            check(shapePlan.paths.write.contains(victim) && shapePlan.paths.delete.contains(victim + "/local-note.txt"),
                    "explicit first-sync deletion authorizes a directory-to-file replacement");

            // --- 可靠性 ---
            String lockDir = dest + "-rel";
            deleteTree(Paths.get(lockDir));
            c.sync(lockDir, new SyncOptions().version("1.0.0"));
            // 进程内并发互斥：同进程两个线程并发 sync 同目录，恰有其一 ELOCKED。
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            Runnable one = () -> {
                try {
                    c.sync(lockDir, new SyncOptions().version("1.0.0"));
                    results.add("ok");
                } catch (QentimArtException e) {
                    results.add(e.code == null ? "err" : e.code);
                }
            };
            Thread th1 = new Thread(one);
            Thread th2 = new Thread(one);
            th1.start();
            th2.start();
            th1.join();
            th2.join();
            long locked = results.stream().filter("ELOCKED"::equals).count();
            long okc = results.stream().filter("ok"::equals).count();
            check(locked == 1 && okc == 1, "in-process concurrent sync of same dir: exactly one ELOCKED " + results);

            // 静默损坏自愈：翻转一个字节 → verify 报告 → 再 sync 复原。
            java.util.TreeSet<String> keys = new java.util.TreeSet<>(tree1.keySet());
            String victim2 = keys.stream().filter(k -> k.startsWith("ui/")).findFirst().orElse(keys.first());
            Path vp = Paths.get(lockDir).resolve(victim2);
            byte[] data = Files.readAllBytes(vp);
            data[0] ^= 0xff;
            Files.write(vp, data);
            Map<String, Object> vr = c.verify(lockDir);
            check(Boolean.FALSE.equals(vr.get("ok")) && ((List<?>) vr.get("corrupted")).contains(victim2),
                    "verify detects silent corruption (" + victim2 + ")");
            c.sync(lockDir, new SyncOptions().version("1.0.0"));
            check(Boolean.TRUE.equals(c.verify(lockDir).get("ok")), "sync heals the corrupted file");

            // mtime+size 盲区：内容被改但大小不变、mtime 被还原 → 普通同步漏检；verifyPaths 强制哈希并自愈。
            java.util.List<String> bs = new ArrayList<>();
            for (String k : new java.util.TreeSet<>(tree1.keySet())) if (k.startsWith("ui/")) bs.add(k);
            String victim3 = bs.size() > 1 ? bs.get(1) : bs.get(0);
            Path bp = Paths.get(lockDir).resolve(victim3);
            long mt = Files.getLastModifiedTime(bp).toMillis();
            byte[] good = Files.readAllBytes(bp);
            byte[] bad = good.clone();
            bad[0] ^= 0xff;
            Runnable tamper = () -> {
                try {
                    Files.write(bp, bad);
                    Files.setLastModifiedTime(bp, java.nio.file.attribute.FileTime.fromMillis(mt)); // 还原 mtime
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            };
            tamper.run();
            c.sync(lockDir, new SyncOptions().version("1.0.0")); // 普通同步可能漏检（取决于本机 mtime 精度）
            boolean missed = java.util.Arrays.equals(Files.readAllBytes(bp), bad);
            System.out.println("  info  fast path " + (missed ? "missed (blind spot reproduced)" : "caught")
                    + " same-size tamper; verifyPaths covers it either way");
            tamper.run(); // 再次篡改，确保确实由 verifyPaths 发现并自愈
            c.sync(lockDir, new SyncOptions().version("1.0.0").verifyPaths(java.util.List.of(victim3)));
            check(java.util.Arrays.equals(Files.readAllBytes(bp), good), "verifyPaths forces a hash check and heals a same-size tamper");
        }

        String fresh = dest + "-fresh";
        deleteTree(Paths.get(fresh));
        try (QentimArtClient c = new QentimArtClient(server, project, token, 16)) {
            SyncResult r = c.sync(fresh);
            double secs = r.elapsedMs / 1000.0;
            System.out.printf("  throughput (fresh %s, concurrency 16): %s in %.2fs = %s/s%n",
                    r.version, mib(r.bytes.written), secs, mib(r.bytes.written / Math.max(secs, 0.001)));
            check(tree(Paths.get(fresh)).equals(tree2), "fresh sync of latest is byte-identical");
        }

        System.exit(failures > 0 ? 1 : 0);
    }
}
