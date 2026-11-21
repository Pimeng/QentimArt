// 协议与 Go / Java 客户端共享的对象上限。网络读取和解压分别计数。
export const MAX_CHUNK_SIZE = 16 << 20;
export const MAX_STORED_SIZE = MAX_CHUNK_SIZE + 1024;
export const MAX_MANIFEST_GZ = 256 << 20;
export const MAX_MANIFEST_RAW = 1 << 30;
export const MAX_JSON = 64 << 20;
