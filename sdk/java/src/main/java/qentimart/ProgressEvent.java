package qentimart;

/** 同步进度回调事件。{@code phase} 取值：resolve / scan / hash / download / cleanup / done / verify。 */
public record ProgressEvent(String phase, long done, long total, long downloadedBytes, long bytesPerSecond) {
}
