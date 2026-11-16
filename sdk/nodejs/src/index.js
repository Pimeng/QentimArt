import path from 'node:path';
import { HttpClient, AssetSyncError } from './http.js';
import { parseManifest, STATE_DIR } from './manifest.js';
import { runSync, runStatus, runVerify, SyncConflictError } from './sync.js';
import { dirBytes, scanDir } from './localfs.js';

export { AssetSyncError, SyncConflictError, STATE_DIR };

/**
 * 下游 AssetSync 客户端。
 *
 *   const client = new AssetSyncClient({ server, project, token });
 *   const result = await client.sync('./Assets', { onProgress });
 */
export class AssetSyncClient {
  constructor({
    server,
    project,
    token,
    concurrency = 8,
    batchBytes = 16 << 20,
    batchChunks = 2048,
    waveBytes = 512 << 20,
    waveFiles = 2048,
    timeoutMs = 60000,
    retries = 3,
  } = {}) {
    if (!server || !project) throw new AssetSyncError('server and project are required');
    this.project = project;
    this.concurrency = concurrency;
    this.batchBytes = batchBytes;
    this.batchChunks = batchChunks;
    this.waveBytes = waveBytes;
    this.waveFiles = waveFiles;
    this.http = new HttpClient({ server, project, token, concurrency, timeoutMs, retries });
  }

  /** 释放连接池中的连接。 */
  close() {
    this.http.close();
  }

  /** 列出所有版本（最新的在前），以及 `latest` / `latestPrerelease`。 */
  listVersions() {
    return this.http.json('GET', '/versions');
  }

  /** 获取版本元数据；`version` 可以是 "latest"。 */
  getVersion(version = 'latest', { prerelease = false } = {}) {
    return this.http.json('GET', `/versions/${encodeURIComponent(version)}${prerelease ? '?prerelease=1' : ''}`);
  }

  /** 服务端计算的两个版本之间的差异（文件列表 + 新增 chunk 字节数）。 */
  diff(from, to = 'latest') {
    return this.http.json('GET', `/versions/${encodeURIComponent(to)}/diff?from=${encodeURIComponent(from)}`);
  }

  /** 下载 manifest（gzip 字节）及其解析到的版本号。 */
  async fetchManifestRaw(version = 'latest', { prerelease = false } = {}) {
    const { body, headers } = await this.http.buffer('GET', `/versions/${encodeURIComponent(version)}/manifest${prerelease ? '?prerelease=1' : ''}`);
    return { version: headers['x-assetsync-version'], gz: body };
  }

  /** 下载并解析 manifest。 */
  async fetchManifest(version = 'latest', opts) {
    const { gz } = await this.fetchManifestRaw(version, opts);
    return parseManifest(gz);
  }

  /**
   * 将 `dir` 同步到指定版本。
   * @param {string} dir
   * @param {object} [opts]
   * @param {string} [opts.version='latest']
   * @param {'overwrite'|'keep'|'fail'} [opts.localChanges='overwrite']
   * @param {boolean} [opts.prerelease=false]  允许 "latest" 解析到预发布版本
   * @param {boolean} [opts.verifyAll=false]   对每个本地文件计算哈希，而不是信任 size+mtime
   * @param {boolean} [opts.dryRun=false]      仅计算同步计划
   * @param {(p:object)=>void} [opts.onProgress]
   * @param {AbortSignal} [opts.signal]
   */
  sync(dir, opts = {}) {
    return runSync(this, dir, opts);
  }

  /** 试运行：sync() 将会执行哪些操作？ */
  plan(dir, opts = {}) {
    return runSync(this, dir, { ...opts, dryRun: true });
  }

  /** 本地状态与上次同步版本的对比（不访问网络）。 */
  status(dir) {
    return runStatus(dir, this.project);
  }

  /** 重新计算当前版本中每个文件的哈希。 */
  verify(dir, onProgress) {
    return runVerify(dir, onProgress);
  }

  /** 资产字节数与 AssetSync 额外开销（`.assetsync` 目录）的对比。 */
  static async storageReport(dir) {
    dir = path.resolve(dir);
    const { files } = await scanDir(dir);
    let assetBytes = 0;
    for (const f of files.values()) assetBytes += f.size;
    const overheadBytes = await dirBytes(path.join(dir, STATE_DIR));
    return { assetBytes, overheadBytes, ratio: assetBytes ? (assetBytes + overheadBytes) / assetBytes : 1 };
  }
}
