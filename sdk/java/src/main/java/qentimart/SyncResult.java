package qentimart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link QentimArtClient#sync} / {@link QentimArtClient#plan} 的结果。字段采用 Java 惯例 camelCase。 */
public final class SyncResult {
    public String project;
    public String version;
    public String previousVersion;   // 可空
    public boolean upToDate;
    public boolean dryRun;
    public FilesInfo files;
    public BytesInfo bytes;
    public long elapsedMs;
    public Long bytesPerSecond;       // 非 dry-run 才有
    public Storage storage;           // 非 dry-run 才有
    public Paths paths;               // dry-run 才有

    public static final class FilesInfo {
        public int total, unchanged, adopted, created, updated, deleted;
        public List<String> kept = new ArrayList<>();
    }

    public static final class BytesInfo {
        public long total, toWrite;
        public Long estimatedDownload, written, reusedLocal, downloadedRaw, downloadedWire;
    }

    public static final class Storage {
        public long assetBytes, overheadBytes;
        public double ratio;
    }

    public static final class Paths {
        public List<String> write, delete, kept;
    }

    /** 机器可读形式（供 CLI --json 输出）。 */
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("project", project);
        m.put("version", version);
        m.put("previousVersion", previousVersion);
        m.put("upToDate", upToDate);
        m.put("dryRun", dryRun);

        LinkedHashMap<String, Object> f = new LinkedHashMap<>();
        f.put("total", files.total);
        f.put("unchanged", files.unchanged);
        f.put("adopted", files.adopted);
        f.put("created", files.created);
        f.put("updated", files.updated);
        f.put("deleted", files.deleted);
        f.put("kept", files.kept);
        m.put("files", f);

        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("total", bytes.total);
        b.put("toWrite", bytes.toWrite);
        if (bytes.estimatedDownload != null) b.put("estimatedDownload", bytes.estimatedDownload);
        if (bytes.written != null) b.put("written", bytes.written);
        if (bytes.reusedLocal != null) b.put("reusedLocal", bytes.reusedLocal);
        if (bytes.downloadedRaw != null) b.put("downloadedRaw", bytes.downloadedRaw);
        if (bytes.downloadedWire != null) b.put("downloadedWire", bytes.downloadedWire);
        m.put("bytes", b);

        m.put("elapsedMs", elapsedMs);
        if (bytesPerSecond != null) m.put("bytesPerSecond", bytesPerSecond);

        if (storage != null) {
            LinkedHashMap<String, Object> s = new LinkedHashMap<>();
            s.put("assetBytes", storage.assetBytes);
            s.put("overheadBytes", storage.overheadBytes);
            s.put("ratio", storage.ratio);
            m.put("storage", s);
        }
        if (paths != null) {
            LinkedHashMap<String, Object> p = new LinkedHashMap<>();
            p.put("write", paths.write);
            p.put("delete", paths.delete);
            p.put("kept", paths.kept);
            m.put("paths", p);
        }
        return m;
    }
}
