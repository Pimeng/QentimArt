import { createHash } from 'node:crypto';
import { inflateRaw } from 'node:zlib';
import { promisify } from 'node:util';
import { AssetSyncError } from './http.js';

const inflateRawAsync = promisify(inflateRaw);

export const FRAME_HEADER_LEN = 41;
export const ENC_RAW = 0;
export const ENC_DEFLATE = 1;
export const ENC_MISSING = 0xff;

/**
 * 解析 chunk 帧流：
 *   hash[32] | enc u8 | rawLen u32be | storedLen u32be | payload[storedLen]
 * 逐个产出 { hash (hex), enc, rawLen, payload }。
 */
export async function* parseFrames(stream) {
  const bufs = [];
  let len = 0;
  const take = (n) => {
    const first = bufs[0];
    if (first.length >= n) {
      const out = first.subarray(0, n);
      if (first.length === n) bufs.shift();
      else bufs[0] = first.subarray(n);
      len -= n;
      return out;
    }
    const out = Buffer.allocUnsafe(n);
    let off = 0;
    while (off < n) {
      const b = bufs[0];
      const k = Math.min(b.length, n - off);
      b.copy(out, off, 0, k);
      off += k;
      if (k === b.length) bufs.shift();
      else bufs[0] = b.subarray(k);
    }
    len -= n;
    return out;
  };
  let hdr = null;
  for await (const chunk of stream) {
    bufs.push(chunk);
    len += chunk.length;
    for (;;) {
      if (!hdr) {
        if (len < FRAME_HEADER_LEN) break;
        const h = take(FRAME_HEADER_LEN);
        hdr = {
          hash: h.toString('hex', 0, 32),
          enc: h[32],
          rawLen: h.readUInt32BE(33),
          storedLen: h.readUInt32BE(37),
        };
      }
      if (len < hdr.storedLen) break;
      const payload = hdr.storedLen > 0 ? take(hdr.storedLen) : Buffer.alloc(0);
      const f = hdr;
      hdr = null;
      f.payload = payload;
      yield f;
    }
  }
  if (hdr || len > 0) throw new AssetSyncError('truncated chunk stream', { code: 'ETRUNCATED' });
}

export function sha256hex(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

/**
 * 解码一个静态 blob 对象（enc u8 | rawLen u32be | payload），校验哈希。
 * 静态模式下每个 chunk 就是这样一个对象（与服务端存储格式一致）。
 */
export async function decodeBlob(buf, hashHex) {
  if (buf.length < 5) throw new AssetSyncError(`chunk ${hashHex}: blob too short`, { code: 'ECORRUPT' });
  return decodeFrame({ hash: hashHex, enc: buf[0], rawLen: buf.readUInt32BE(1), payload: buf.subarray(5) });
}

/** 将帧 payload 解码为原始字节并校验其哈希。 */
export async function decodeFrame(f) {
  let raw;
  if (f.enc === ENC_RAW) raw = f.payload;
  else if (f.enc === ENC_DEFLATE) raw = await inflateRawAsync(f.payload);
  else if (f.enc === ENC_MISSING) throw new AssetSyncError(`chunk ${f.hash} is missing on the server`, { code: 'EMISSING' });
  else throw new AssetSyncError(`unknown chunk encoding ${f.enc}`);
  if (raw.length !== f.rawLen || sha256hex(raw) !== f.hash) {
    throw new AssetSyncError(`chunk ${f.hash} failed verification`, { code: 'ECORRUPT' });
  }
  return raw;
}
