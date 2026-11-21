import http from 'node:http';
import https from 'node:https';
import { MAX_JSON } from './limits.js';

export class QentimArtError extends Error {
  constructor(message, { status, code, cause } = {}) {
    super(message, cause ? { cause } : undefined);
    this.name = 'QentimArtError';
    this.status = status;
    this.code = code;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function readCapped(stream, maxBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of stream) {
    total += chunk.length;
    if (total > maxBytes) throw new QentimArtError('response exceeds size limit', { code: 'ETOOLARGE' });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, total);
}

/**
 * 精简的 keep-alive HTTP 客户端。直接使用 node:http（而非 fetch），
 * 以降低单次请求开销，并让响应体成为真正的 Node 流。
 */
export class HttpClient {
  constructor({ server, project, token, concurrency = 8, timeoutMs = 60000, retries = 3 }) {
    const u = new URL(server);
    this.base = u;
    this.prefix = `${u.pathname.replace(/\/+$/, '')}/api/v1/projects/${encodeURIComponent(project)}`;
    this.mod = u.protocol === 'https:' ? https : http;
    this.agent = new this.mod.Agent({ keepAlive: true, maxSockets: concurrency * 2, keepAliveMsecs: 30000 });
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.retries = retries;
  }

  close() {
    this.agent.destroy();
  }

  /**
   * 发起一次请求。成功时（status < 300）resolve 为响应体尚未被消费的
   * IncomingMessage。
   */
  raw(method, path, { body, headers = {}, signal } = {}) {
    return new Promise((resolve, reject) => {
      const h = { ...headers };
      if (this.token) h.authorization = `Bearer ${this.token}`;
      if (body) h['content-length'] = body.length;
      const req = this.mod.request(
        {
          protocol: this.base.protocol,
          hostname: this.base.hostname,
          port: this.base.port,
          method,
          path: this.prefix + path,
          headers: h,
          agent: this.agent,
          signal,
        },
        async (res) => {
          if (res.statusCode >= 300) {
            let msg;
            try {
              msg = (await readCapped(res, 8192)).toString('utf8');
              try { msg = JSON.parse(msg).error || msg; } catch {}
            } catch (err) {
              msg = err.message;
            }
            reject(new QentimArtError(`${method} ${path}: HTTP ${res.statusCode}: ${msg}`, { status: res.statusCode }));
            return;
          }
          resolve(res);
        },
      );
      req.setTimeout(this.timeoutMs, () => req.destroy(new QentimArtError(`${method} ${path}: timeout`, { code: 'ETIMEDOUT' })));
      req.on('error', reject);
      req.end(body);
    });
  }

  async buffer(method, path, opts) {
    return this.withRetry(async () => {
      const res = await this.raw(method, path, opts);
      return { body: await readCapped(res, opts?.maxBytes ?? MAX_JSON), headers: res.headers };
    }, opts?.signal);
  }

  async json(method, path, opts) {
    const { body } = await this.buffer(method, path, opts);
    return JSON.parse(body.toString('utf8'));
  }

  /** 对网络错误和 5xx 响应进行退避重试。 */
  async withRetry(fn, signal) {
    let lastErr;
    for (let attempt = 0; attempt <= this.retries; attempt++) {
      if (signal?.aborted) throw signal.reason ?? new QentimArtError('aborted', { code: 'ABORT_ERR' });
      try {
        return await fn(attempt);
      } catch (err) {
        lastErr = err;
        if (err?.name === 'AbortError' || signal?.aborted || ['ETOOLARGE', 'ECORRUPT'].includes(err.code)) throw err;
        if (err instanceof QentimArtError && err.status && err.status < 500) throw err;
        if (attempt < this.retries) await sleep(300 * 2 ** attempt);
      }
    }
    throw lastErr;
  }
}
