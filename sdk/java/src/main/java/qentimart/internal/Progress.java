package qentimart.internal;

/** 内部进度回调（节流在实现方处理）。 */
public interface Progress {
    void emit(String phase, long done, long total, boolean force);
}
