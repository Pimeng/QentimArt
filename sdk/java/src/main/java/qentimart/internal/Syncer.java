package qentimart.internal;

import qentimart.Chunk;
import qentimart.QentimArtException;
import qentimart.internal.LocalFs.PositionedFile;
import qentimart.internal.Planner.Plan;
import qentimart.internal.Planner.Source;
import qentimart.internal.Planner.SourceIndex;
import qentimart.internal.Planner.WriteTask;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
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

    public static void applyWrites(Path root, String policy, Plan plan, SourceIndex sources, Stats stats,
                                   Map<String, Long> newTracked, long writeBytes, Progress progress,
                                   AtomicBoolean cancel, ChunkFetcher fetcher, long waveBytes, int waveFiles) throws IOException {
        Path tmpDir = root.resolve(Wire.STATE_DIR).resolve(State.TMP_DIR);
        LocalFs.rmTree(tmpDir);
        Files.createDirectories(tmpDir);
        Set<String> deleteSet = new HashSet<>(plan.toDelete);
        int seq = 0;
        for (List<WriteTask> wave : waves(plan.toWrite, waveBytes, waveFiles)) {
            checkCancel(cancel);
            List<Path> tmps = new ArrayList<>();
            for (int i = 0; i < wave.size(); i++) tmps.add(tmpDir.resolve((seq++) + ".part"));
            List<PositionedFile> handles = new ArrayList<>(Collections.nCopies(wave.size(), (PositionedFile) null));
            try {
                applyWave(root, policy, wave, tmps, handles, sources, stats, newTracked, writeBytes, progress, cancel, deleteSet, fetcher);
            } finally {
                for (PositionedFile h : handles) if (h != null) h.close();
            }
        }
    }

    private static void applyWave(Path root, String policy, List<WriteTask> wave, List<Path> tmps, List<PositionedFile> handles,
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
                                r = PositionedFile.open(root.resolve(s.path), false, 0);
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
                    if (data.length == s.size && Wire.sha256Hex(data).equals(h)) {
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
            WriteTask w = wave.get(i);
            Path target = root.resolve(w.t.path());
            ensureParent(root, w.t.path(), deleteSet, policy);
            if (w.reason.equals("replace-dir")) LocalFs.rmTree(target);
            try {
                LocalFs.replaceRetry(tmps.get(i), target);
            } catch (IOException e) {
                throw new QentimArtException("cannot replace " + w.t.path() + " (is it open in another program?): " + e.getMessage(), e);
            }
            newTracked.put(w.t.path(), LocalFs.mtimeMs(target));
            sources.replaced(w.t.path(), w.t.chunks());
        }
    }

    private static void ensureParent(Path root, String rel, Set<String> deleteSet, String policy) throws IOException {
        int slash = rel.lastIndexOf('/');
        if (slash < 0) return;
        String parent = rel.substring(0, slash);
        try {
            Files.createDirectories(root.resolve(parent));
            return;
        } catch (FileAlreadyExistsException | NotDirectoryException e) {
            // 需要目录的位置上存在一个文件（例如 "a" -> "a/b"），下面逐段处理
        }
        String[] segs = parent.split("/");
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) acc.append('/');
            acc.append(segs[i]);
            String p = acc.toString();
            Path ap = root.resolve(p);
            if (Files.exists(ap, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(ap)) {
                if (!deleteSet.contains(p) && !policy.equals("overwrite")) {
                    throw new QentimArtException(p + " is a file but " + rel + " needs it to be a directory");
                }
                Files.deleteIfExists(ap);
                deleteSet.remove(p);
            }
        }
        Files.createDirectories(root.resolve(parent));
    }

    /** 删除操作放在最后执行，以便被删除的文件仍可作为块来源。 */
    public static void deleteFiles(Path root, List<String> paths) throws IOException {
        for (String p : paths) {
            Path ap = root.resolve(p);
            // 该路径可能已不存在，或已变为目标版本中的目录（文件 -> 目录）。
            if (!Files.exists(ap, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(ap)) continue;
            Files.deleteIfExists(ap);
            LocalFs.pruneEmptyDirs(root, p);
        }
    }
}
