package qentimart;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** {@link QentimArtClient#sync} 的参数（链式 setter）。 */
public final class SyncOptions {
    /** 目标版本，默认 "latest"。 */
    public String version = "latest";
    /** 本地改动策略：overwrite（默认）/ keep / fail。 */
    public String localChanges = "overwrite";
    /** 允许 "latest" 解析到预发布版本。 */
    public boolean prerelease = false;
    /** 对所有本地文件重新计算哈希，不信任 size + mtime。 */
    public boolean verifyAll = false;
    /** 试运行（dry run），不改动磁盘。 */
    public boolean dryRun = false;
    /** 安全开关：从未同步过的目录是否删除不属于目标版本的文件。 */
    public boolean deleteUntrackedOnFirstSync = false;
    /** 进度回调。 */
    public Consumer<ProgressEvent> onProgress = null;
    /** 取消标志；调用 set(true) 即可中止同步。 */
    public AtomicBoolean cancel = null;

    public SyncOptions() {
    }

    public SyncOptions version(String v) { this.version = v; return this; }
    public SyncOptions localChanges(String v) { this.localChanges = v; return this; }
    public SyncOptions prerelease(boolean v) { this.prerelease = v; return this; }
    public SyncOptions verifyAll(boolean v) { this.verifyAll = v; return this; }
    public SyncOptions dryRun(boolean v) { this.dryRun = v; return this; }
    public SyncOptions deleteUntrackedOnFirstSync(boolean v) { this.deleteUntrackedOnFirstSync = v; return this; }
    public SyncOptions onProgress(Consumer<ProgressEvent> v) { this.onProgress = v; return this; }
    public SyncOptions cancel(AtomicBoolean v) { this.cancel = v; return this; }
}
