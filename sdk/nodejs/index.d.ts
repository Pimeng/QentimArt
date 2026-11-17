export declare const STATE_DIR: '.qentimart';

export declare class QentimArtError extends Error {
  status?: number;
  code?: string;
}

export declare class SyncConflictError extends QentimArtError {
  conflicts: string[];
}

export interface ClientOptions {
  /** 静态模式：项目静态树的公开根地址（如 https://game.pages.dev），设置后无需 server/token。 */
  baseUrl?: string;
  /** 动态模式：服务端地址。 */
  server?: string;
  project?: string;
  token?: string;
  /** 并行的 chunk 获取请求数（默认 8）。 */
  concurrency?: number;
  /** 每个获取请求的最大原始字节数（默认 16 MiB）。 */
  batchBytes?: number;
  /** 每个获取请求的最大 chunk 数（默认 2048）。 */
  batchChunks?: number;
  /** 文件按批次（wave）写入，每批最多这么多字节（默认 512 MiB）…… */
  waveBytes?: number;
  /** ……以及最多这么多个文件（默认 2048）。 */
  waveFiles?: number;
  timeoutMs?: number;
  retries?: number;
}

export interface VersionInfo {
  version: string;
  createdAt: string;
  prerelease: boolean;
  fileCount: number;
  totalSize: number;
  chunkCount: number;
  manifestSize: number;
  baseVersion?: string;
  deltaBytes: number;
}

export interface VersionList {
  project: string;
  versions: VersionInfo[];
  latest?: string;
  latestPrerelease?: string;
}

export interface Diff {
  from: string;
  to: string;
  added: string[];
  modified: string[];
  removed: string[];
  downloadBytes: number;
  totalBytes: number;
}

export interface ManifestChunk {
  hash: string;
  size: number;
  offset: number;
}

export interface ManifestFile {
  path: string;
  size: number;
  hash: string;
  chunks: ManifestChunk[];
}

export interface Manifest {
  version: string;
  createdAt: string;
  files: Map<string, ManifestFile>;
}

export type LocalChangesPolicy = 'overwrite' | 'keep' | 'fail';

export interface Progress {
  phase: 'resolve' | 'scan' | 'hash' | 'download' | 'cleanup' | 'done' | 'verify';
  done: number;
  total: number;
  downloadedBytes?: number;
  bytesPerSecond?: number;
}

export interface SyncOptions {
  /** 要同步到的版本（默认 "latest"）。 */
  version?: string;
  /**
   * 如何处理本地已修改或未跟踪的文件：
   *  - overwrite（默认）：恢复为服务器版本，并删除多余的文件
   *  - keep：保持这些文件不变，只更新其余文件
   *  - fail：在做出任何修改之前抛出 SyncConflictError
   */
  localChanges?: LocalChangesPolicy;
  /**
   * 是否删除「未跟踪」文件（本地存在、不在目标版本、也从不受 SDK 管理的文件）。
   * 默认 false：即便 localChanges='overwrite' 也绝不删除这类文件，适合「只同步某子树、
   * 其余本地文件保留」的场景。设为 true 才会在 overwrite 下清理这些多余文件。
   * 注意：清单内「被移除的受管文件」始终照常清理，不受此开关影响。
   */
  deleteUntracked?: boolean;
  /**
   * 安全开关：对于从未同步过的目录（没有 .qentimart 状态），即使
   * deleteUntracked=true，未跟踪文件仍会被保留，除非此项也为 true
   * （防止误传了错误的 dir 把整个目录清空）。
   */
  deleteUntrackedOnFirstSync?: boolean;
  /** 允许 "latest" 解析到预发布版本。 */
  prerelease?: boolean;
  /** 对每个文件计算哈希，而不是信任 size + mtime。 */
  verifyAll?: boolean;
  /**
   * 对指定的重要文件强制哈希校验（即使 size + mtime 未变），普通文件仍走快速检测。
   * 每项为资源目录内的相对路径；以 "/" 结尾表示目录前缀（该目录下所有文件）。
   */
  verifyPaths?: string[];
  dryRun?: boolean;
  onProgress?: (p: Progress) => void;
  signal?: AbortSignal;
}

export interface StorageReport {
  assetBytes: number;
  overheadBytes: number;
  /** (assetBytes + overheadBytes) / assetBytes */
  ratio: number;
}

export interface SyncResult {
  project: string;
  version: string;
  previousVersion: string | null;
  upToDate: boolean;
  dryRun: boolean;
  files: {
    total: number;
    unchanged: number;
    adopted: number;
    created: number;
    updated: number;
    deleted: number;
    kept: string[];
  };
  bytes: {
    total: number;
    toWrite: number;
    written: number;
    reusedLocal: number;
    downloadedRaw: number;
    downloadedWire: number;
    estimatedDownload?: number;
  };
  paths?: { write: string[]; delete: string[]; kept: string[] };
  elapsedMs: number;
  bytesPerSecond?: number;
  storage: StorageReport | null;
}

export interface StatusResult {
  synced: boolean;
  project: string | null;
  version: string | null;
  syncedAt?: string;
  clean?: boolean;
  modified: string[];
  missing: string[];
  untracked: string[];
}

export type Mode = 'api' | 'static';

export declare class QentimArtClient {
  constructor(opts: ClientOptions);
  readonly mode: Mode;
  readonly project: string;
  close(): void;
  listVersions(): Promise<VersionList>;
  getVersion(version?: string, opts?: { prerelease?: boolean }): Promise<VersionInfo>;
  diff(from: string, to?: string): Promise<Diff>;
  fetchManifestRaw(version?: string, opts?: { prerelease?: boolean }): Promise<{ version: string; gz: Buffer }>;
  fetchManifest(version?: string, opts?: { prerelease?: boolean }): Promise<Manifest>;
  sync(dir: string, opts?: SyncOptions): Promise<SyncResult>;
  plan(dir: string, opts?: Omit<SyncOptions, 'dryRun'>): Promise<SyncResult>;
  status(dir: string): Promise<StatusResult>;
  verify(dir: string, onProgress?: (p: Progress) => void): Promise<{ version: string; ok: boolean; corrupted: string[] }>;
  static storageReport(dir: string): Promise<StorageReport>;
}
