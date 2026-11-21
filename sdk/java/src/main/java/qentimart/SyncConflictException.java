package qentimart;

import java.util.List;

/** 当 {@code localChanges="fail"} 且本地改动阻止同步时抛出。 */
public class SyncConflictException extends QentimArtException {
    /** 发生冲突的文件路径列表。 */
    public final List<String> conflicts;

    public SyncConflictException(List<String> conflicts) {
        super(message(conflicts), -1, "ECONFLICT");
        this.conflicts = conflicts;
    }

    private static String message(List<String> conflicts) {
        String head = String.join(", ", conflicts.subList(0, Math.min(10, conflicts.size())))
                + (conflicts.size() > 10 ? ", ..." : "");
        return conflicts.size()
                + " locally modified/untracked file(s) block the sync: " + head;
    }

    public List<String> conflicts() {
        return conflicts;
    }
}
