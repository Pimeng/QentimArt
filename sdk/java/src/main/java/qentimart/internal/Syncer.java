package qentimart.internal;

import qentimart.Chunk;
import qentimart.QentimArtException;
import qentimart.internal.LocalFs.PositionedFile;
import qentimart.internal.Planner.Plan;
import qentimart.internal.Planner.Source;
import qentimart.internal.Planner.SourceIndex;
import qentimart.internal.Planner.WriteTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;

/** 执行计划：复用本地块、并行批量拉取、原子替换。 */
public final class Syncer {
    private Syncer() {
    }

    /** 由 QentimArtClient 提供的块拉取能力（动态批量 / 静态逐块）。 */
    public interface ChunkFetcher {
        void fetch(List<String> hashes, ToLongFunction<String> sizeOf,
                   BiConsumer<String, byte[]> onChunk, Stats stats, AtomicBoolean cancel);
    }

    private interface WriteChunk {
        void run(Need n, byte[] data) throws IOException;
    }

    static final class Need {
        final long size;
        final List<Object[]> dests = new ArrayList<>(); // (PositionedFile, Long offset)

        Need(long size) {
            this.size = size;
        }
    }

    static void checkCancel(AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) throw new QentimArtException("sync cancelled", -1, "ECANCELED");
    }

    static List<List<WriteTask>> waves(List<WriteTask> tasks, long maxBytes, int maxFiles) {
        List<List<WriteTask>> out = new ArrayList<>();
        List<WriteTask> cur = new ArrayList<>();
        long size = 0;
        for (WriteTask w : tasks) {
            if (!cur.isEmpty() && (size + w.t.size() > maxBytes || cur.size() >= maxFiles)) {
                out.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(w);
            size += w.t.size();
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    public static void applyWrites(Path root, Plan plan, SourceIndex sources, Stats stats,
                                   Map<String, Long> newTracked, long writeBytes, Progress progress,
                                   AtomicBoolean cancel, ChunkFetcher fetcher, long waveBytes, int waveFiles) throws IOException {
        Path tmpDir = State.tmpDir(root);
        LocalFs.rmTree(tmpDir);
        Files.createDirectory(tmpDir);
        Set<String> deleteSet = new HashSet<>(plan.toDelete);
        int seq = 0;
        for (List<WriteTask> wave : waves(plan.toWrite, waveBytes, waveFiles)) {
            checkCancel(cancel);
            List<Path> tmps = new ArrayList<>();
            for (int i = 0; i < wave.size(); i++) tmps.add(tmpDir.resolve((seq++) + ".part"));
            List<PositionedFile> handles = new ArrayList<>(Collections.nCopies(wave.size(), (PositionedFile) null));
            try {
                applyWave(root, wave, tmps, handles, sources, stats, newTracked, writeBytes, progress, cancel, deleteSet, fetcher);
            } finally {
                for (PositionedFile h : handles) if (h != null) h.close();
            }
        }
    }

    private static void applyWave(Path root, List<WriteTask> wave, List<Path> tmps, List<PositionedFile> handles,
                                  SourceIndex sources, Stats stats, Map<String, Long> newTracked, long writeBytes,
                                  Progress progress, AtomicBoolean cancel, Set<String> deleteSet, ChunkFetcher fetcher) throws IOException {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < wave.size(); i++) idx.add(i);
        Pool.run(idx, 16, i -> handles.set(i, PositionedFile.open(tmps.get(i), true, wave.get(i).t.size())));

        Map<String, Need> need = new LinkedHashMap<>();
        for (int i = 0; i < wave.size(); i++) {
            for (Chunk c : wave.get(i).t.chunks()) {
                Need n = need.computeIfAbsent(c.hash(), k -> new Need(c.size()));
                n.dests.add(new Object[]{handles.get(i), c.offset()});
            }
        }

        WriteChunk writeChunk = (n, data) -> {
            if (data.length != n.size) throw new QentimArtException("chunk length differs from manifest", -1, "ECORRUPT");
            for (Object[] d : n.dests) ((PositionedFile) d[0]).writeAt((Long) d[1], data);
            long done = stats.written.addAndGet((long) data.length * n.dests.size());
            progress.emit("download", done, writeBytes, false);
        };

        // 1) 复用本地已存在的块
        Map<String, PositionedFile> readers = new HashMap<>();
        Object readersLock = new Object();
        List<String> remaining = Collections.synchronizedList(new ArrayList<>());
        try {
            Pool.run(need.entrySet(), 8, entry -> {
                String h = entry.getKey();
                Need n = entry.getValue();
                for (Source s : sources.candidates(h)) {
                    PositionedFile fh;
                    synchronized (readersLock) {
                        if (!readers.containsKey(s.path)) {
                            PositionedFile r;
                            try {
                                r = PositionedFile.open(LocalFs.checkedParent(root, s.path), false, 0);
                            } catch (IOException ex) {
                                r = null;
                            }
                            readers.put(s.path, r);
                        }
                        fh = readers.get(s.path);
                    }
                    if (fh == null) continue;
                    byte[] data;
                    try {
                        data = fh.readAt(s.offset, (int) s.size);
                    } catch (IOException ex) {
                        continue;
                    }
                    if (data.length == s.size && s.size == n.size && Wire.sha256Hex(data).equals(h)) {
                        writeChunk.run(n, data);
                        stats.reusedLocal.addAndGet(data.length);
                        return;
                    }
                }
                remaining.add(h);
            });
        } finally {
            for (PositionedFile r : readers.values()) if (r != null) r.close();
        }

        // 2) 获取其余的块（动态模式批量拉帧流，静态模式逐块 GET，由 client 决定）
        if (!remaining.isEmpty()) {
            List<String> rem = new ArrayList<>(remaining);
            fetcher.fetch(rem, h -> need.get(h).size,
                    (h, raw) -> {
                        try {
                            writeChunk.run(need.get(h), raw);
                        } catch (IOException e) {
                            throw new QentimArtException("write failed: " + e.getMessage(), e);
                        }
                    }, stats, cancel);
        }

        // 3) 关闭文件、移动到目标位置、记录状态
        for (int i = 0; i < handles.size(); i++) {
            handles.get(i).close();
            handles.set(i, null);
        }
        for (int i = 0; i < wave.size(); i++) {
            var file = wave.get(i).t;
            if (file.chunks().size() > 1 && !LocalFs.hashFile(tmps.get(i)).equals(file.hash())) {
                throw new QentimArtException("file hash differs from manifest: " + file.path(), -1, "ECORRUPT");
            }
        }
        for (int i = 0; i < wave.size(); i++) {
            WriteTask w = wave.get(i);
            Path target = root.resolve(w.t.path());
            ensureParent(root, w.t.path(), deleteSet);
            if (w.reason.equals("replace-dir")) removePlannedDirectory(root, target, deleteSet);
            if (w.reason.equals("create")) requireAbsentDestination(target, w.t.path());
            try {
                LocalFs.replaceRetry(tmps.get(i), target);
            } catch (IOException e) {
                throw new QentimArtException("cannot replace " + w.t.path() + " (is it open in another program?): " + e.getMessage(), e);
            }
            newTracked.put(w.t.path(), LocalFs.mtimeMs(target));
            sources.replaced(w.t.path(), w.t.chunks());
        }
    }

    private static void requireAbsentDestination(Path target, String rel) throws IOException {
        try {
            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        // An existing name can alias this path on a case-insensitive filesystem.
        throw new QentimArtException("local file blocks creation: " + rel, -1, "ECONFLICT");
    }

    // 逐段创建父目录，绝不跟随符号链接：若某个已存在的祖先是符号链接，拒绝写入，
    // 以免清单路径合法但本地父目录指向 root 之外时，原子替换把文件写到 root 外（逃逸）。
    private static void ensureParent(Path root, String rel, Set<String> deleteSet) throws IOException {
        int slash = rel.lastIndexOf('/');
        if (slash < 0) return;
        String parent = rel.substring(0, slash);
        String[] segs = parent.split("/");
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) acc.append('/');
            acc.append(segs[i]);
            String p = acc.toString();
            Path ap = root.resolve(p);
            BasicFileAttributes at;
            try {
                at = Files.readAttributes(ap, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                at = null; // 不存在
            }
            if (at != null && !LocalFs.realDirectory(ap, at)) {
                if (!at.isRegularFile()) throw new QentimArtException("unsafe path " + rel + ": " + p, -1, "EUNSAFE");
                // 该位置是普通文件，但需要它是目录（例如 "a" -> "a/b"）。
                if (!deleteSet.contains(p)) {
                    throw new QentimArtException(p + " is a file but " + rel + " needs it to be a directory");
                }
                Files.deleteIfExists(ap);
                deleteSet.remove(p);
                Files.createDirectory(ap);
            } else if (at == null) {
                Files.createDirectory(ap);
            }
        }
    }

    /** Remove only files authorized by the plan. Newly appeared children must survive a replacement. */
    private static void removePlannedDirectory(Path root, Path target, Set<String> deleteSet) throws IOException {
        LocalFs.requireDirectory(target);
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                LocalFs.requireDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                if (!attrs.isRegularFile() || !deleteSet.contains(rel)) {
                    throw new QentimArtException("local file blocks directory replacement: " + rel, -1, "ECONFLICT");
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 删除操作放在最后执行，以便被删除的文件仍可作为块来源。 */
    public static void deleteFiles(Path root, List<String> paths) throws IOException {
        for (String p : paths) {
            Path ap = root.resolve(p);
            // 该路径可能已不存在，或已变为目标版本中的目录（文件 -> 目录）。
            // 用 NOFOLLOW：真实目录才跳过，符号链接按条目本身删除、绝不跟随到外部目标。
            if (!Files.exists(ap, LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(ap, LinkOption.NOFOLLOW_LINKS)) continue;
            LocalFs.checkedParent(root, p);
            Files.deleteIfExists(ap);
            LocalFs.pruneEmptyDirs(root, p);
        }
    }
}
