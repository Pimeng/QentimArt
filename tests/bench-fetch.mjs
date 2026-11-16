// 纯网络基准测试：获取 + 解码 + 校验某个版本的每个 chunk，
// 不涉及磁盘读写。用于将服务端/网络/CPU 吞吐量与磁盘写入速度区分开。
//
//   node tests/bench-fetch.mjs SERVER PROJECT TOKEN [VERSION] [CONCURRENCY]
import { AssetSyncClient } from '../sdk/nodejs/src/index.js';
import { parseFrames, decodeFrame } from '../sdk/nodejs/src/frames.js';

const [server, project, token, version = 'latest', conc = '16'] = process.argv.slice(2);
const client = new AssetSyncClient({ server, project, token, concurrency: Number(conc) });
const m = await client.fetchManifest(version);
const sizes = new Map();
for (const f of m.files.values()) for (const c of f.chunks) sizes.set(c.hash, c.size);
const hashes = [...sizes.keys()];
const batches = [];
let cur = [];
let bytes = 0;
for (const h of hashes) {
  cur.push(h);
  bytes += sizes.get(h);
  if (bytes >= 16 << 20 || cur.length >= 2048) {
    batches.push(cur);
    cur = [];
    bytes = 0;
  }
}
if (cur.length) batches.push(cur);

let raw = 0;
let wire = 0;
const t0 = Date.now();
let next = 0;
await Promise.all(
  Array.from({ length: Number(conc) }, async () => {
    while (next < batches.length) {
      const b = batches[next++];
      const res = await client.http.raw('POST', '/chunks/fetch', { body: Buffer.concat(b.map((h) => Buffer.from(h, 'hex'))) });
      for await (const f of parseFrames(res)) {
        const r = await decodeFrame(f);
        raw += r.length;
        wire += f.payload.length;
      }
    }
  }),
);
const s = (Date.now() - t0) / 1000;
console.log(`fetched ${hashes.length} chunks: ${(raw / 1048576).toFixed(0)} MiB raw (${(wire / 1048576).toFixed(0)} MiB wire) in ${s.toFixed(2)}s = ${(raw / 1048576 / s).toFixed(0)} MiB/s raw, ${(wire / 1048576 / s).toFixed(0)} MiB/s wire`);
client.close();
