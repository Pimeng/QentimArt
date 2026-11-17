import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import { QentimArtError } from './http.js';
import { pool } from './localfs.js';
import { decodeBlob } from './frames.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 按响应的 Content-Encoding 透明解压：node:http(s) 不会自动处理传输层编码，
// 而很多 CDN / 对象存储会对 index.json 之类的响应强制 gzip（即便请求 identity）。
// 以响应头为准解码，对不带该头的 .gz 清单和原始 chunk 零影响。
function decodeBody(buf, encoding) {
  switch (String(encoding || '').toLowerCase().trim()) {
    case 'gzip':
    case 'x-gzip':
      return zlib.gunzipSync(buf);
    case 'deflate':
      return zlib.inflateSync(buf);
    case 'br':
      return zlib.brotliDecompressSync(buf);
    default:
      return buf;
  }
}

/**
 * 静态模式的只读传输：从一个公开的 baseUrl（对象存储 / CDN / Cloudflare Pages）
 * 按对象键 GET index.json、manifest 和 chunk。不需要鉴权。
 */
export class StaticTransport {
  constructor({ baseUrl, concurrency = 8, timeoutMs = 60000, retries = 3 }) {
    const u = new URL(baseUrl);
    this.origin = u;
    this.basePath = u.pathname.replace(/\/+$/, '');
    this.mod = u.protocol === 'https:' ? https : http;
    this.agent = new this.mod.Agent({ keepAlive: true, maxSockets: concurrency * 2, keepAliveMsecs: 30000 });
    this.timeoutMs = timeoutMs;
    this.retries = retries;
    this.concurrency = concurrency;
    this._index = null;
    this._indexAt = 0;
  }

  close() {
    this.agent.destroy();
  }

  _get(relPath, signal) {
    return new Promise((resolve, reject) => {
      const req = this.mod.request(
        {
          protocol: this.origin.protocol,
          hostname: this.origin.hostname,
          port: this.origin.port,
          method: 'GET',
          path: `${this.basePath}/${relPath}`,
          agent: this.agent,
          signal,
        },
        (res) => {
          if (res.statusCode >= 300) {
            res.resume();
            reject(new QentimArtError(`GET ${relPath}: HTTP ${res.statusCode}`, { status: res.statusCode }));
            return;
          }
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => {
            try {
              resolve(decodeBody(Buffer.concat(chunks), res.headers['content-encoding']));
            } catch (err) {
              reject(new QentimArtError(`GET ${relPath}: cannot decode response body: ${err.message}`, { code: err.code, cause: err }));
            }
          });
          res.on('error', reject);
        },
      );
      req.setTimeout(this.timeoutMs, () => req.destroy(new QentimArtError(`GET ${relPath}: timeout`, { code: 'ETIMEDOUT' })));
      req.on('error', reject);
      req.end();
    });
  }

  async getBuffer(relPath, signal) {
    let last;
    for (let attempt = 0; attempt <= this.retries; attempt++) {
      if (signal?.aborted) throw signal.reason ?? new QentimArtError('aborted', { code: 'ABORT_ERR' });
      try {
        return await this._get(relPath, signal);
      } catch (err) {
        last = err;
        if (err?.name === 'AbortError' || signal?.aborted) throw err;
        if (err instanceof QentimArtError && err.status && err.status < 500 && err.status !== 429) throw err;
        if (attempt < this.retries) await sleep(300 * 2 ** attempt);
      }
    }
    throw last;
  }

  /** 读取根索引（短时缓存，用于在一次同步内去重 index.json 的重复请求）。 */
  async index(signal) {
    const now = Date.now();
    if (this._index && now - this._indexAt < 5000) return this._index;
    const buf = await this.getBuffer('index.json', signal);
    this._index = JSON.parse(buf.toString('utf8'));
    this._indexAt = now;
    return this._index;
  }

  /** 按哈希逐个 GET chunk，并发 concurrency；每块解码并校验后交给 onChunk。 */
  async fetchChunks({ hashes, onChunk, stats, signal }) {
    await pool(hashes, this.concurrency, async (h) => {
      const buf = await this.getBuffer(`chunks/${h.slice(0, 2)}/${h}`, signal);
      const raw = await decodeBlob(buf, h);
      stats.downloadedWire += buf.length;
      stats.downloadedRaw += raw.length;
      await onChunk(h, raw);
    });
  }
}
