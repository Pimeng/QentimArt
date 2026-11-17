package qentimart.internal;

import qentimart.QentimArtClient;
import qentimart.QentimArtException;
import qentimart.StorageReport;
import qentimart.SyncConflictException;
import qentimart.SyncOptions;
import qentimart.SyncResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** qentimart-java：Java SDK 的命令行封装。 */
public final class Cli {
    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] argv) {
        Map<String, String> opt = new HashMap<>();
        Set<String> flags = new HashSet<>();
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                switch (key) {
                    case "prerelease":
                    case "verify-all":
                    case "delete-untracked":
                    case "delete-untracked-on-first-sync":
                    case "json":
                        flags.add(key);
                        break;
                    default:
                        if (i + 1 >= argv.length) {
                            System.err.println("error: --" + key + " needs a value");
                            return 2;
                        }
                        opt.put(key, argv[++i]);
                }
            } else {
                pos.add(a);
            }
        }
        if (pos.isEmpty()) {
            System.err.println("usage: qentimart-java <sync|plan|status|verify|versions|report> [--flags]");
            return 2;
        }
        String cmd = pos.get(0);
        String server = opt.getOrDefault("server", System.getenv("QENTIMART_SERVER"));
        String project = opt.getOrDefault("project", System.getenv("QENTIMART_PROJECT"));
        String token = opt.getOrDefault("token", System.getenv("QENTIMART_TOKEN"));
        String baseUrl = opt.getOrDefault("base-url", System.getenv("QENTIMART_BASE_URL"));
        String dir = opt.get("dir");
        String version = opt.getOrDefault("version", "latest");
        String localChanges = opt.getOrDefault("local-changes", "overwrite");
        int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "8"));
        boolean json = flags.contains("json");

        try {
            if (cmd.equals("report")) {
                if (dir == null) {
                    System.err.println("error: --dir is required");
                    return 2;
                }
                StorageReport r = QentimArtClient.storageReport(dir);
                if (json) {
                    System.out.println(Json.write(r.toMap(), true));
                } else {
                    System.out.printf("assets %s, overhead %s, ratio %.3f%%%n",
                            fmt(r.assetBytes()), fmt(r.overheadBytes()), r.ratio() * 100);
                }
                return 0;
            }

            QentimArtClient client;
            if (baseUrl != null && !baseUrl.isEmpty()) {
                client = QentimArtClient.staticClient(baseUrl, project, concurrency);
            } else {
                if (server == null) {
                    System.err.println("error: --server is required");
                    return 2;
                }
                if (project == null) {
                    System.err.println("error: --project is required");
                    return 2;
                }
                client = new QentimArtClient(server, project, token, concurrency);
            }

            try (client) {
                switch (cmd) {
                    case "versions": {
                        Map<String, Object> v = client.listVersions();
                        if (json) {
                            System.out.println(Json.write(v, true));
                            break;
                        }
                        Object latest = v.get("latest");
                        StringBuilder sb = new StringBuilder();
                        Object versions = v.get("versions");
                        if (versions instanceof List) {
                            for (Object o : (List<?>) versions) {
                                Map<?, ?> x = (Map<?, ?>) o;
                                sb.append(String.format("%s %-16s %10s  +%-10s %d files  %s%n",
                                        Objects.equals(x.get("version"), latest) ? "*" : " ",
                                        x.get("version"), fmt(num(x.get("totalSize"))), fmt(num(x.get("deltaBytes"))),
                                        (long) num(x.get("fileCount")), x.get("createdAt")));
                            }
                        }
                        System.out.print(sb.length() == 0 ? "(no versions)\n" : sb.toString());
                        break;
                    }
                    case "status": {
                        if (dir == null) {
                            System.err.println("error: --dir is required");
                            return 2;
                        }
                        System.out.println(Json.write(client.status(dir), true));
                        break;
                    }
                    case "verify": {
                        if (dir == null) {
                            System.err.println("error: --dir is required");
                            return 2;
                        }
                        System.out.println(Json.write(client.verify(dir), true));
                        break;
                    }
                    case "sync":
                    case "plan": {
                        if (dir == null) {
                            System.err.println("error: --dir is required");
                            return 2;
                        }
                        SyncOptions o = new SyncOptions().version(version).localChanges(localChanges)
                                .prerelease(flags.contains("prerelease"))
                                .verifyAll(flags.contains("verify-all"))
                                .deleteUntracked(flags.contains("delete-untracked"))
                                .deleteUntrackedOnFirstSync(flags.contains("delete-untracked-on-first-sync"));
                        String vpStr = opt.get("verify-paths");
                        if (vpStr != null && !vpStr.isEmpty()) {
                            List<String> vpl = new ArrayList<>();
                            for (String s : vpStr.split(",")) if (!s.isEmpty()) vpl.add(s);
                            o.verifyPaths(vpl);
                        }
                        if (!json) {
                            long[] last = {0};
                            o.onProgress(pr -> {
                                long now = System.currentTimeMillis();
                                if (now - last[0] < 1000 && !pr.phase().equals("done")) return;
                                last[0] = now;
                                double pct = pr.total() > 0 ? pr.done() * 100.0 / pr.total() : 0.0;
                                System.err.printf("  [%s] %.1f%%  %s/%s  %s/s%n",
                                        pr.phase(), pct, fmt(pr.done()), fmt(pr.total()), fmt(pr.bytesPerSecond()));
                            });
                        }
                        SyncResult r = cmd.equals("plan") ? client.plan(dir, o) : client.sync(dir, o);
                        if (json) {
                            System.out.println(Json.write(r.toMap(), true));
                            break;
                        }
                        SyncResult.FilesInfo f = r.files;
                        SyncResult.BytesInfo b = r.bytes;
                        System.out.printf("%s %s %s -> %s%s%n", r.dryRun ? "plan" : "synced", r.project,
                                r.previousVersion == null ? "(none)" : r.previousVersion, r.version,
                                r.upToDate ? " (already up to date)" : "");
                        System.out.printf("files: %d total, %d unchanged, %d adopted, %d created, %d updated, %d deleted, %d kept%n",
                                f.total, f.unchanged, f.adopted, f.created, f.updated, f.deleted, f.kept.size());
                        if (r.dryRun) {
                            System.out.printf("would write %s, estimated download %s%n", fmt(b.toWrite), fmt(b.estimatedDownload));
                        } else {
                            System.out.printf("wrote %s: %s reused locally, %s downloaded (%s on the wire) in %.2fs%n",
                                    fmt(b.written), fmt(b.reusedLocal), fmt(b.downloadedRaw), fmt(b.downloadedWire), r.elapsedMs / 1000.0);
                            SyncResult.Storage s = r.storage;
                            System.out.printf("storage: assets %s + overhead %s = %.3f%%%n",
                                    fmt(s.assetBytes), fmt(s.overheadBytes), s.ratio * 100);
                        }
                        break;
                    }
                    default:
                        System.err.println("unknown command " + cmd);
                        return 2;
                }
            }
            return 0;
        } catch (SyncConflictException e) {
            for (String c : e.conflicts) System.err.println(c);
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (QentimArtException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    static String fmt(Number n) {
        return fmt(n == null ? 0.0 : n.doubleValue());
    }

    static String fmt(double n) {
        String[] u = {"B", "KiB", "MiB", "GiB", "TiB"};
        int i = 0;
        while (n >= 1024 && i < u.length - 1) {
            n /= 1024;
            i++;
        }
        return i == 0 ? String.format("%d%s", (long) n, u[i]) : String.format("%.1f%s", n, u[i]);
    }
}
