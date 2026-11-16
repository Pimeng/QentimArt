package qentimart.internal;

import qentimart.Chunk;
import qentimart.Manifest;
import qentimart.ManifestFile;
import qentimart.internal.LocalFs.LocalFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将本地目录与目标 manifest 比较，生成写入/删除计划，并建立本地块来源索引。 */
public final class Planner {
    private Planner() {
    }

    public static final class WriteTask {
        public final ManifestFile t;
        public final String reason; // create | update | replace-dir
        public final LocalFile local; // 可空
        public final boolean clean;

        WriteTask(ManifestFile t, String reason, LocalFile local, boolean clean) {
            this.t = t;
            this.reason = reason;
            this.local = local;
            this.clean = clean;
        }
    }

    public static final class Plan {
        public final List<WriteTask> toWrite = new ArrayList<>();
        public final List<String> toDelete = new ArrayList<>();
        public final List<String> unchanged = new ArrayList<>();
        public final List<String> adopted = new ArrayList<>();
        public final List<String> kept = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public final Map<String, Long> newTracked = new HashMap<>();
    }

    public static final class Context {
        public Path root;
        public String policy;
        public boolean verifyAll;
        public boolean deleteUntrackedOnFirstSync;
        public Map<String, LocalFile> files;
        public Set<String> dirs;
        public Manifest old;            // 可空
        public Map<String, Long> tracked;
        public boolean hasState;
        public Progress progress;
        public java.util.function.Predicate<String> verifyWanted = p -> false;
    }

    public static Plan buildPlan(Context ctx, Manifest target) {
        String policy = ctx.policy;
        Map<String, ManifestFile> old = ctx.old != null ? ctx.old.files : Collections.emptyMap();
        Plan plan = new Plan();
        Object lock = new Object();
        List<Object[]> toHash = new ArrayList<>(); // (ManifestFile t, LocalFile l)

        for (ManifestFile t : target.files.values()) {
            LocalFile l = ctx.files.get(t.path());
            if (l == null) {
                if (ctx.dirs.contains(t.path()) && !policy.equals("overwrite")) {
                    (policy.equals("keep") ? plan.kept : plan.conflicts).add(t.path());
                } else {
                    plan.toWrite.add(new WriteTask(t, ctx.dirs.contains(t.path()) ? "replace-dir" : "create", null, false));
                }
                continue;
            }
            if (isClean(ctx, old, t.path(), l)) {
                if (old.get(t.path()).hash().equals(t.hash())) {
                    plan.unchanged.add(t.path());
                    plan.newTracked.put(t.path(), l.mtimeMs());
                } else {
                    plan.toWrite.add(new WriteTask(t, "update", l, true));
                }
                continue;
            }
            if (l.size() != t.size()) {
                decideModified(plan, policy, t, l);
                continue;
            }
            toHash.add(new Object[]{t, l});
        }

        long[] total = {0};
        for (Object[] it : toHash) total[0] += ((LocalFile) it[1]).size();
        long[] done = {0};

        Pool.run(toHash, 4, item -> {
            ManifestFile t = (ManifestFile) item[0];
            LocalFile l = (LocalFile) item[1];
            String h;
            try {
                h = LocalFs.hashFile(ctx.root.resolve(t.path()));
            } catch (IOException e) {
                h = "";
            }
            boolean matched;
            synchronized (lock) {
                done[0] += l.size();
                if (h.equals(t.hash())) {
                    plan.adopted.add(t.path());
                    plan.newTracked.put(t.path(), l.mtimeMs());
                    matched = true;
                } else {
                    matched = false;
                }
            }
            ctx.progress.emit("hash", done[0], total[0], false);
            if (!matched) {
                synchronized (lock) {
                    decideModified(plan, policy, t, l);
                }
            }
        });

        for (Map.Entry<String, LocalFile> e : ctx.files.entrySet()) {
            String p = e.getKey();
            if (target.files.containsKey(p)) continue;
            // 安全措施：在从未同步过的目录中，未知文件仅在明确要求时才会被删除。
            boolean mayDelete = policy.equals("overwrite") && (ctx.hasState || ctx.deleteUntrackedOnFirstSync);
            if (isClean(ctx, old, p, e.getValue()) || mayDelete) {
                plan.toDelete.add(p);
            } else if (policy.equals("overwrite") || policy.equals("keep")) {
                plan.kept.add(p);
            } else {
                plan.conflicts.add(p);
            }
        }

        plan.toWrite.sort(Comparator.comparing(w -> w.t.path()));
        Collections.sort(plan.toDelete);
        Collections.sort(plan.kept);
        Collections.sort(plan.conflicts);
        return plan;
    }

    private static boolean isClean(Context ctx, Map<String, ManifestFile> old, String p, LocalFile l) {
        // size+mtime 快速检测存在盲区（内容改但大小不变、mtime 被还原）；
        // verifyAll 对所有文件、verifyWanted 对指定重要文件强制走哈希校验。
        if (ctx.verifyAll || ctx.verifyWanted.test(p)) return false;
        ManifestFile o = old.get(p);
        Long tr = ctx.tracked.get(p);
        return o != null && tr != null && tr == l.mtimeMs() && o.size() == l.size();
    }

    private static void decideModified(Plan plan, String policy, ManifestFile t, LocalFile l) {
        if (policy.equals("overwrite")) plan.toWrite.add(new WriteTask(t, "update", l, false));
        else if (policy.equals("keep")) plan.kept.add(t.path());
        else plan.conflicts.add(t.path());
    }

    // ------------------------------------------------------------ 本地块来源索引

    public static final class Source {
        public final String path;
        public final long offset;
        public final long size;
        public final int gen;

        Source(String path, long offset, long size, int gen) {
            this.path = path;
            this.offset = offset;
            this.size = size;
            this.gen = gen;
        }
    }

    /** 磁盘上（可能）存有某个块的位置。使用前始终会进行校验。 */
    public static final class SourceIndex {
        private final Map<String, List<Source>> byHash = new HashMap<>();
        private final Map<String, Integer> gen = new HashMap<>();
        private final Object lock = new Object();

        public void add(String path, List<Chunk> chunks, long maxSize) {
            int g = gen.getOrDefault(path, 0);
            for (Chunk c : chunks) {
                if (c.offset() + c.size() > maxSize) continue;
                List<Source> arr = byHash.computeIfAbsent(c.hash(), k -> new ArrayList<>());
                if (arr.size() < 4) arr.add(new Source(path, c.offset(), c.size(), g));
            }
        }

        public void add(String path, List<Chunk> chunks) {
            add(path, chunks, Long.MAX_VALUE);
        }

        public void replaced(String path, List<Chunk> chunks) {
            synchronized (lock) {
                gen.merge(path, 1, Integer::sum);
                add(path, chunks, Long.MAX_VALUE);
            }
        }

        public List<Source> candidates(String h) {
            synchronized (lock) {
                List<Source> arr = byHash.get(h);
                if (arr == null) return Collections.emptyList();
                List<Source> out = new ArrayList<>();
                for (Source s : arr) {
                    if (s.gen == gen.getOrDefault(s.path, 0)) out.add(s);
                }
                return out;
            }
        }
    }

    public static SourceIndex buildSources(Context ctx, Manifest target, Plan plan) {
        SourceIndex src = new SourceIndex();
        Map<String, ManifestFile> old = ctx.old != null ? ctx.old.files : Collections.emptyMap();
        for (String p : plan.unchanged) src.add(p, target.files.get(p).chunks());
        for (String p : plan.adopted) src.add(p, target.files.get(p).chunks());
        for (WriteTask w : plan.toWrite) {
            if (w.local == null) continue;
            ManifestFile o = old.get(w.t.path());
            if (o != null) src.add(w.t.path(), o.chunks(), w.local.size());
            if (!w.clean) src.add(w.t.path(), w.t.chunks(), w.local.size()); // 推测：与目标布局相同
        }
        for (String p : plan.toDelete) {
            ManifestFile o = old.get(p);
            LocalFile l = ctx.files.get(p);
            if (o != null && l != null) src.add(p, o.chunks(), l.size()); // 被移动/重命名的资源从本地复制
        }
        return src;
    }

    public static long estimateDownload(Plan plan, SourceIndex sources) {
        Set<String> seen = new java.util.HashSet<>();
        long n = 0;
        for (WriteTask w : plan.toWrite) {
            for (Chunk c : w.t.chunks()) {
                if (!seen.add(c.hash())) continue;
                if (sources.candidates(c.hash()).isEmpty()) n += c.size();
            }
        }
        return n;
    }
}
