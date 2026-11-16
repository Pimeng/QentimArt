package qentimart;

/** 清单中的一个块：{@code hash} 为块内容的 SHA-256，{@code size} 为块长度，{@code offset} 为块在文件中的偏移。 */
public record Chunk(String hash, long size, long offset) {
}
