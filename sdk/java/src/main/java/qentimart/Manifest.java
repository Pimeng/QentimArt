package qentimart;

import java.util.Map;

/** 解析后的版本清单。{@code files} 按路径顺序排列（LinkedHashMap）。 */
public final class Manifest {
    public final String version;
    public final String createdAt;
    public final Map<String, ManifestFile> files;

    public Manifest(String version, String createdAt, Map<String, ManifestFile> files) {
        this.version = version;
        this.createdAt = createdAt;
        this.files = files;
    }

    public String version() {
        return version;
    }

    public String createdAt() {
        return createdAt;
    }

    public Map<String, ManifestFile> files() {
        return files;
    }
}
