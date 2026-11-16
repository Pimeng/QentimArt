package qentimart;

import java.util.List;

/** 清单中的一个文件条目。{@code chunks} 为按偏移排序的块列表（单块或空文件时可能只有一个块或为空）。 */
public record ManifestFile(String path, long size, String hash, List<Chunk> chunks) {
}
