package qentimart;

import qentimart.internal.HttpTransport;
import qentimart.internal.Json;
import qentimart.internal.LocalFs;
import qentimart.internal.LocalFs.LocalFile;
import qentimart.internal.LocalFs.Scan;
import qentimart.internal.Planner;
import qentimart.internal.Planner.Plan;
import qentimart.internal.Planner.SourceIndex;
import qentimart.internal.Planner.WriteTask;
import qentimart.internal.State;
import qentimart.internal.Stats;
import qentimart.internal.StaticTransport;
import qentimart.internal.Syncer;
import qentimart.internal.Wire;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * QentimArt 下游客户端。两种模式：
 *
 * <pre>
 * 动态：new QentimArtClient("http://assets:8080", "mygame", "&lt;token&gt;")  —— 走服务端 HTTP API
 * 静态：QentimArtClient.staticClient("https://game.pages.dev")             —— 从公开静态树只读拉取，无需鉴权
 * </pre>
 */
public final class QentimArtClient implements AutoCloseable {
    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public final String mode; // "api" | "static"
    public final String project;
    private final HttpTransport http;     // 可空
    private final StaticTransport stat;    // 可空
    private final int concurrency;
    private final long batchBytes = 16L << 20;
    private final int batchChunks = 2048;
    private final long waveBytes = 512L << 20;
    private final int waveFiles = 2048;

    /** 动态客户端，默认并发 8。 */
    public QentimArtClient(String server, String project, String token) {
        this(server, project, token, 8);
    }

    /** 动态客户端。 */
    public QentimArtClient(String server, String project, String token, int concurrency) {
        if (server == null || project == null) {
            throw new QentimArtException("server and project are required (or use QentimArtClient.staticClient for static mode)");
        }
        this.mode = "api";
        this.project = project;
        this.concurrency = concurrency;
        this.http = new HttpTransport(server, project, token, 60000, 3);
        this.stat = null;
    }

    private QentimArtClient(String baseUrl, String project, int concurrency, boolean isStatic) {
        this.mode = "static";
        this.project = project;
        this.concurrency = concurrency;
        this.stat = new StaticTransport(baseUrl, concurrency, 60000, 3);
        this.http = null;
    }

    /** 静态客户端（只读，无需鉴权），默认并发 8。 */
    public static QentimArtClient staticClient(String baseUrl) {
        return new QentimArtClient(baseUrl, null, 8, true);
    }

    /** 静态客户端（只读，无需鉴权）。 */
    public static QentimArtClient staticClient(String baseUrl, String project, int concurrency) {
        return new QentimArtClient(baseUrl, project, concurrency, true);
    }

    @Override
    public void close() {
        if (http != null) http.close();
        if (stat != null) stat.close();
    }

    // ------------------------------------------------------------ 服务端 API

    @SuppressWarnings("unchecked")
    public Map<String, Object> listVersions() {
        if (mode.equals("static")) return stat.index(null);
        return (Map<String, Object>) http.json("GET", "/versions");
    }

    public Map<String, Object> getVersion() {
        return getVersion("latest", false);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getVersion(String version, boolean prerelease) {
        if (mode.equals("static")) return StaticTransport.versionFromIndex(stat.index(null), version, prerelease);
        return (Map<String, Object>) http.json("GET",
                "/versions/" + HttpTransport.pctEncode(version) + (prerelease ? "?prerelease=1" : ""));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> diff(String from, String to) {
        if (mode.equals("static")) return Wire.diffManifests(fetchManifest(from, false), fetchManifest(to, false));
        return (Map<String, Object>) http.json("GET",
                "/versions/" + HttpTransport.pctEncode(to) + "/diff?from=" + HttpTransport.pctEncode(from));
    }

    static final class ManifestRaw {
        final String version;
        final byte[] gz;

        ManifestRaw(String version, byte[] gz) {
            this.version = version;
            this.gz = gz;
        }
    }

    ManifestRaw fetchManifestRaw(String version, boolean prerelease) {
        if (mode.equals("static")) {
            Map<String, Object> vi = getVersion(version, prerelease);
            String v = vi.get("version").toString();
            byte[] gz = stat.getBuffer("versions/" + v + "/manifest.json.gz", Wire.MAX_MANIFEST_GZ, null);
            return new ManifestRaw(v, gz);
        }
        HttpTransport.Download d = http.download("GET",
                "/versions/" + HttpTransport.pctEncode(version) + "/manifest" + (prerelease ? "?prerelease=1" : ""),
                Wire.MAX_MANIFEST_GZ, "X-QentimArt-Version");
        return new ManifestRaw(d.header, d.body);
    }

    public Manifest fetchManifest(String version, boolean prerelease) {
        return Wire.parseManifest(fetchManifestRaw(version, prerelease).gz);
    }

    /** 取一批块，每块解码校验后回调 onChunk(hash, raw)。动态批量 / 静态逐块。 */
    void fetchChunks(List<String> hashes, ToLongFunction<String> sizeOf, BiConsumer<String, byte[]> onChunk,
                     Stats stats, AtomicBoolean cancel) {
        if (mode.equals("static")) {
            stat.fetchChunks(hashes, onChunk, stats, cancel);
            return;
        }
        List<List<String>> batches = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        long curBytes = 0;
        for (String h : hashes) {
            cur.add(h);
            curBytes += sizeOf.applyAsLong(h);
            if (curBytes >= batchBytes || cur.size() >= batchChunks) {
                batches.add(cur);
                cur = new ArrayList<>();
                curBytes = 0;
            }
        }
        if (!cur.isEmpty()) batches.add(cur);
        qentimart.internal.Pool.run(batches, concurrency, b -> apiFetchBatch(b, onChunk, stats, cancel));
    }

    private void apiFetchBatch(List<String> batch, BiConsumer<String, byte[]> onChunk, Stats stats, AtomicBoolean cancel) {
        Set<String> pending = Collections.synchronizedSet(new LinkedHashSet<>(batch));
        http.withRetry(n -> {
            List<String> snapshot;
            synchronized (pending) {
                snapshot = new ArrayList<>(pending);
            }
            byte[] body = new byte[snapshot.size() * 32];
            for (int i = 0; i < snapshot.size(); i++) {
                System.arraycopy(Wire.unhex(snapshot.get(i)), 0, body, i * 32, 32);
            }
            HttpResponse<InputStream> resp = http.stream("POST", "/chunks/fetch", body);
            try (InputStream in = resp.body()) {
                Wire.readFrames(in, (hashHex, enc, rawLen, payload) -> {
                    if (cancel != null && cancel.get()) throw new QentimArtException("sync cancelled", -1, "ECANCELED");
                    if (!pending.contains(hashHex)) throw new QentimArtException("server sent unexpected chunk " + hashHex);
                    byte[] raw = Wire.decodeChunk(hashHex, enc, rawLen, payload);
                    stats.downloadedWire.addAndGet(payload.length + Wire.FRAME_HEADER_LEN);
                    stats.downloadedRaw.addAndGet(raw.length);
                    onChunk.accept(hashHex, raw);
                    pending.remove(hashHex);
                });
            }
            if (!pending.isEmpty()) {
                throw new QentimArtException("server stream ended with " + pending.size() + " chunk(s) missing", -1, "ETRUNCATED");
            }
            return null;
        }, cancel);
    }

    // ------------------------------------------------------------ 同步

    /** sync() 的试运行（dry run）。 */
    public SyncResult plan(String dir, SyncOptions opts) {
        SyncOptions o = opts == null ? new SyncOptions() : opts;
        o.dryRun = true;
        return sync(dir, o);
    }

    public SyncResult sync(String dir) {
        return sync(dir, new SyncOptions());
    }

    public SyncResult sync(String dir, SyncOptions opts) {
        SyncOptions o = opts == null ? new SyncOptions() : opts;
        String policy = o.localChanges;
        if (!policy.equals("overwrite") && !policy.equals("keep") && !policy.equals("fail")) {
            throw new QentimArtException("invalid localChanges " + policy);
        }
        long started = System.nanoTime();
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new QentimArtException("cannot create " + root + ": " + e.getMessage(), e);
        }
        Stats stats = new Stats();
        final long[] lastEmit = {0};
        final Object emitLock = new Object();
        Consumer<ProgressEvent> cb = o.onProgress;
        qentimart.internal.Progress progress = (phase, done, total, force) -> {
            if (cb == null) return;
            long now = System.nanoTime();
            synchronized (emitLock) {
                if (!force && (now - lastEmit[0]) < 200_000_000L && done < total) return;
                lastEmit[0] = now;
            }
            double secs = Math.max(0.001, (now - started) / 1e9);
            long wire = stats.downloadedWire.get();
            cb.accept(new ProgressEvent(phase, done, total, wire, (long) (wire / secs)));
        };

        Runnable release = o.dryRun ? () -> {
        } : State.acquireLock(root);
        try {
            State.Read sr = State.read(root);
            boolean sameProject = sr.state != null && Objects.equals(sr.state.get("project"), project);

            progress.emit("resolve", 0, 1, true);
            String version = o.version;
            if (version.equals("latest")) version = getVersion("latest", o.prerelease).get("version").toString();
            byte[] targetGz;
            if (sameProject && sr.manifest != null && sr.manifest.version.equals(version)) {
                targetGz = sr.gz;
            } else {
                targetGz = fetchManifestRaw(version, false).gz;
            }
            Manifest target = Wire.parseManifest(targetGz);

            progress.emit("scan", 0, 1, true);
            Scan scan = LocalFs.scanDir(root);
            Planner.Context ctx = new Planner.Context();
            ctx.root = root;
            ctx.policy = policy;
            ctx.verifyAll = o.verifyAll;
            ctx.deleteUntracked = o.deleteUntracked;
            ctx.deleteUntrackedOnFirstSync = o.deleteUntrackedOnFirstSync;
            ctx.files = scan.files();
            ctx.dirs = scan.dirs();
            ctx.old = sameProject ? sr.manifest : null;
            ctx.tracked = sameProject ? trackedMap(sr.state) : new HashMap<>();
            ctx.hasState = sameProject && sr.manifest != null;
            ctx.progress = progress;
            // 重要文件强制哈希校验：精确路径，或以 "/" 结尾的目录前缀。
            List<String> vp = o.verifyPaths == null ? Collections.emptyList() : o.verifyPaths;
            Set<String> vexact = new java.util.HashSet<>();
            List<String> vpre = new ArrayList<>();
            for (String x : vp) {
                if (x.endsWith("/")) vpre.add(x);
                else vexact.add(x);
            }
            ctx.verifyWanted = p -> vexact.contains(p) || vpre.stream().anyMatch(p::startsWith);

            Plan plan = Planner.buildPlan(ctx, target);
            if (!plan.conflicts.isEmpty() && policy.equals("fail")) throw new SyncConflictException(plan.conflicts);

            SourceIndex sources = Planner.buildSources(ctx, target, plan);
            long totalBytes = 0;
            for (ManifestFile f : target.files.values()) totalBytes += f.size();
            long writeBytes = 0;
            for (WriteTask w : plan.toWrite) writeBytes += w.t.size();

            SyncResult res = new SyncResult();
            res.project = project;
            res.version = version;
            res.previousVersion = sameProject ? str(sr.state.get("version")) : null;
            res.upToDate = plan.toWrite.isEmpty() && plan.toDelete.isEmpty();
            res.dryRun = o.dryRun;
            SyncResult.FilesInfo fi = new SyncResult.FilesInfo();
            fi.total = target.files.size();
            fi.unchanged = plan.unchanged.size();
            fi.adopted = plan.adopted.size();
            int created = 0, updated = 0;
            for (WriteTask w : plan.toWrite) {
                if (w.reason.equals("update")) updated++;
                else created++;
            }
            fi.created = created;
            fi.updated = updated;
            fi.deleted = plan.toDelete.size();
            fi.kept = plan.kept;
            res.files = fi;
            SyncResult.BytesInfo bi = new SyncResult.BytesInfo();
            bi.total = totalBytes;
            bi.toWrite = writeBytes;
            res.bytes = bi;

            if (o.dryRun) {
                bi.estimatedDownload = Planner.estimateDownload(plan, sources);
                SyncResult.Paths pths = new SyncResult.Paths();
                List<String> wp = new ArrayList<>();
                for (WriteTask w : plan.toWrite) wp.add(w.t.path());
                pths.write = wp;
                pths.delete = plan.toDelete;
                pths.kept = plan.kept;
                res.paths = pths;
                res.elapsedMs = (long) ((System.nanoTime() - started) / 1e6);
                return res;
            }

            Map<String, Long> newTracked = plan.newTracked;
            if (!plan.toWrite.isEmpty()) {
                try {
                    Syncer.applyWrites(root, policy, plan, sources, stats, newTracked, writeBytes, progress, o.cancel,
                            (hashes, sizeOf, onChunk, st, cancel) -> fetchChunks(hashes, sizeOf, onChunk, st, cancel),
                            waveBytes, waveFiles);
                } catch (IOException e) {
                    throw new QentimArtException("sync failed: " + e.getMessage(), e);
                }
            }

            progress.emit("cleanup", 0, plan.toDelete.size(), true);
            try {
                Syncer.deleteFiles(root, plan.toDelete);
                LocalFs.rmTree(root.resolve(Wire.STATE_DIR).resolve(State.TMP_DIR));
            } catch (IOException e) {
                throw new QentimArtException("cleanup failed: " + e.getMessage(), e);
            }

            Map<String, Object> newState = new LinkedHashMap<>();
            newState.put("formatVersion", 1L);
            newState.put("project", project);
            newState.put("version", version);
            newState.put("syncedAt", ISO_MS.format(Instant.now()));
            newState.put("files", new TreeMap<>(newTracked));
            try {
                State.write(root, newState, targetGz);
            } catch (IOException e) {
                throw new QentimArtException("cannot write state: " + e.getMessage(), e);
            }

            double elapsed = Math.max(0.001, (System.nanoTime() - started) / 1e9);
            bi.written = stats.written.get();
            bi.reusedLocal = stats.reusedLocal.get();
            bi.downloadedRaw = stats.downloadedRaw.get();
            bi.downloadedWire = stats.downloadedWire.get();
            res.elapsedMs = (long) (elapsed * 1000);
            res.bytesPerSecond = (long) (stats.downloadedWire.get() / elapsed);
            long keptBytes = 0;
            for (String p : plan.kept) {
                LocalFile l = scan.files().get(p);
                if (l != null) keptBytes += l.size();
            }
            long overhead = Json.writeBytes(newState, false).length + targetGz.length;
            long assetBytes = totalBytes + keptBytes;
            SyncResult.Storage stg = new SyncResult.Storage();
            stg.assetBytes = assetBytes;
            stg.overheadBytes = overhead;
            stg.ratio = assetBytes > 0 ? (double) (assetBytes + overhead) / assetBytes : 1.0;
            res.storage = stg;
            progress.emit("done", 1, 1, true);
            return res;
        } finally {
            release.run();
        }
    }

    // ------------------------------------------------------------ 仅本地

    public Map<String, Object> status(String dir) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        State.Read sr = State.read(root);
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        if (sr.state == null || sr.manifest == null || !Objects.equals(sr.state.get("project"), project)) {
            r.put("synced", false);
            r.put("project", sr.state != null ? sr.state.get("project") : null);
            r.put("version", null);
            r.put("modified", new ArrayList<>());
            r.put("missing", new ArrayList<>());
            r.put("untracked", new ArrayList<>());
            return r;
        }
        Scan scan = LocalFs.scanDir(root);
        Map<String, Long> tracked = trackedMap(sr.state);
        List<String> modified = Collections.synchronizedList(new ArrayList<>());
        List<String> missing = new ArrayList<>();
        List<ManifestFile> suspicious = new ArrayList<>();
        for (ManifestFile f : sr.manifest.files.values()) {
            LocalFile l = scan.files().get(f.path());
            if (l == null) missing.add(f.path());
            else if (l.size() != f.size()) modified.add(f.path());
            else if (!Objects.equals(tracked.get(f.path()), l.mtimeMs())) suspicious.add(f);
        }
        qentimart.internal.Pool.run(suspicious, 4, f -> {
            try {
                if (!LocalFs.hashFile(root.resolve(f.path())).equals(f.hash())) modified.add(f.path());
            } catch (IOException e) {
                modified.add(f.path());
            }
        });
        List<String> untracked = new ArrayList<>();
        for (String p : scan.files().keySet()) if (!sr.manifest.files.containsKey(p)) untracked.add(p);
        Collections.sort(untracked);
        List<String> mod = new ArrayList<>(modified);
        Collections.sort(mod);
        Collections.sort(missing);
        r.put("synced", true);
        r.put("project", sr.state.get("project"));
        r.put("version", sr.state.get("version"));
        r.put("syncedAt", sr.state.get("syncedAt"));
        r.put("clean", mod.isEmpty() && missing.isEmpty() && untracked.isEmpty());
        r.put("modified", mod);
        r.put("missing", missing);
        r.put("untracked", untracked);
        return r;
    }

    public Map<String, Object> verify(String dir) {
        return verify(dir, null);
    }

    public Map<String, Object> verify(String dir, Consumer<ProgressEvent> onProgress) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        State.Read sr = State.read(root);
        if (sr.state == null || sr.manifest == null) throw new QentimArtException(root + " has not been synced yet");
        List<String> bad = Collections.synchronizedList(new ArrayList<>());
        long total = 0;
        for (ManifestFile f : sr.manifest.files.values()) total += f.size();
        final long ftotal = total;
        AtomicLong done = new AtomicLong();
        qentimart.internal.Pool.run(sr.manifest.files.values(), 4, f -> {
            boolean ok;
            try {
                ok = LocalFs.hashFile(root.resolve(f.path())).equals(f.hash());
            } catch (IOException e) {
                ok = false;
            }
            if (!ok) bad.add(f.path());
            long d = done.addAndGet(f.size());
            if (onProgress != null) onProgress.accept(new ProgressEvent("verify", d, ftotal, 0, 0));
        });
        List<String> badSorted = new ArrayList<>(bad);
        Collections.sort(badSorted);
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("version", sr.state.get("version"));
        r.put("ok", badSorted.isEmpty());
        r.put("corrupted", badSorted);
        return r;
    }

    /** 资源字节数与 QentimArt 额外开销（.qentimart 目录）的对比。 */
    public static StorageReport storageReport(String dir) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        Scan scan = LocalFs.scanDir(root);
        long asset = 0;
        for (LocalFile l : scan.files().values()) asset += l.size();
        long overhead = LocalFs.dirBytes(root.resolve(Wire.STATE_DIR));
        double ratio = asset > 0 ? (double) (asset + overhead) / asset : 1.0;
        return new StorageReport(asset, overhead, ratio);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> trackedMap(Map<String, Object> state) {
        Map<String, Long> out = new HashMap<>();
        Object f = state.get("files");
        if (f instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) f).entrySet()) {
                out.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
