import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { test } from 'node:test';
import { gzipSync, deflateRawSync } from 'node:zlib';
import { QentimArtClient } from '../src/index.js';
import { decodeFrame, parseFrames, sha256hex } from '../src/frames.js';
import { readCapped } from '../src/http.js';
import { parseManifest } from '../src/manifest.js';
import { MAX_CHUNK_SIZE } from '../src/limits.js';

const empty = (path) => ({ path, size: 0, hash: sha256hex(Buffer.alloc(0)) });
const manifest = (files) => gzipSync(JSON.stringify({ formatVersion: 1, version: '1.0.0', files }));

async function workspace(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'qentimart-test-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return dir;
}

function fixtureClient(t, source, files) {
  const client = new QentimArtClient({ baseUrl: source });
  client.fetchManifestRaw = async () => ({ gz: manifest(files) });
  t.after(() => client.close());
  return client;
}

test('manifest rejects nonportable paths, collisions and inconsistent lengths', () => {
  for (const p of ['one:two', 'AUX.png', 'name.', '.QENTIMART/file']) {
    assert.throws(() => parseManifest(manifest([empty(p)])), /unsafe/);
  }
  for (const other of ['asset', 'ASSET', 'asset/detail']) {
    assert.throws(() => parseManifest(manifest([empty('asset'), empty(other)])), /conflicting/);
  }
  for (const size of [-1, 0.5, MAX_CHUNK_SIZE + 1]) {
    assert.throws(() => parseManifest(manifest([{ ...empty('a'), size }])), /invalid/);
  }
});

test('frame headers, compressed output and response data respect byte limits', async () => {
  const raw = Buffer.from('test');
  const frame = { hash: sha256hex(raw), enc: 1, rawLen: raw.length, payload: deflateRawSync(raw) };
  assert.deepEqual(await decodeFrame(frame, raw.length), raw);
  await assert.rejects(decodeFrame({ ...frame, rawLen: 3 }), { code: 'ECORRUPT' });
  await assert.rejects(decodeFrame(frame, 3), { code: 'ECORRUPT' });
  const header = Buffer.alloc(41);
  header.writeUInt32BE(MAX_CHUNK_SIZE + 1, 33);
  await assert.rejects(parseFrames(Readable.from([header])).next(), { code: 'ECORRUPT' });
  await assert.rejects(readCapped(Readable.from([raw, raw]), 7), { code: 'ETOOLARGE' });
});

test('protects untracked names and directory contents during type changes', async (t) => {
  const dir = await workspace(t);
  await fs.mkdir(path.join(dir, 'asset'));
  await fs.writeFile(path.join(dir, 'asset', 'notes'), 'keep');
  const opts = { version: '1.0.0' };
  const client = fixtureClient(t, 'https://example.org', [empty('asset')]);
  await assert.rejects(client.sync(dir, opts), { code: 'ECONFLICT' });
  assert.equal(await fs.readFile(path.join(dir, 'asset', 'notes'), 'utf8'), 'keep');
  await client.sync(dir, { ...opts, deleteUntracked: true, deleteUntrackedOnFirstSync: true });
  assert.ok((await fs.lstat(path.join(dir, 'asset'))).isFile());
  await fs.writeFile(path.join(dir, 'constructor'), 'keep');
  await client.sync(dir, opts);
  assert.equal(await fs.readFile(path.join(dir, 'constructor'), 'utf8'), 'keep');
});

test('same version in two sources resolves independently', async (t) => {
  const dir = await workspace(t);
  const opts = { version: '1.0.0' };
  const first = fixtureClient(t, 'https://example.org/a', [empty('first')]);
  const second = fixtureClient(t, 'https://example.org/b', [empty('second')]);
  await first.sync(dir, opts);
  await second.sync(dir, opts);
  assert.ok((await fs.stat(path.join(dir, 'second'))).isFile());
  assert.ok((await fs.stat(path.join(dir, 'first'))).isFile());
  assert.equal((await second.status(dir)).synced, true);
  assert.equal((await first.status(dir)).synced, false);
});

test('cached timestamps cannot add files to the previous manifest', async (t) => {
  const dir = await workspace(t);
  const opts = { version: '1.0.0' };
  const client = fixtureClient(t, 'https://example.org', []);
  await client.sync(dir, opts);
  const notes = path.join(dir, 'notes');
  const statePath = path.join(dir, '.qentimart', 'state.json');
  await fs.writeFile(notes, 'keep');
  const state = JSON.parse(await fs.readFile(statePath, 'utf8'));
  state.files.notes = Math.floor((await fs.stat(notes)).mtimeMs);
  await fs.writeFile(statePath, JSON.stringify(state));
  assert.deepEqual((await client.sync(dir, opts)).files.kept, ['notes']);
  await fs.writeFile(statePath, JSON.stringify(state));
  await fs.writeFile(path.join(dir, '.qentimart', 'manifest.json.gz'), 'damaged');
  assert.deepEqual((await client.sync(dir, { ...opts, deleteUntracked: true })).files.kept, ['notes']);
  assert.equal(await fs.readFile(notes, 'utf8'), 'keep');
});

test('multi-chunk file hash is checked before installation', async (t) => {
  const dir = await workspace(t);
  const chunk = Buffer.from('a');
  const ref = [sha256hex(chunk), chunk.length];
  const file = { path: 'asset', size: 2, hash: sha256hex(Buffer.from('bb')), chunks: [ref, ref] };
  const client = fixtureClient(t, 'https://example.org', [file]);
  client.fetchChunks = async ({ onChunk }) => onChunk(ref[0], chunk);
  await assert.rejects(client.sync(dir, { version: '1.0.0' }), { code: 'ECORRUPT' });
  await assert.rejects(fs.stat(path.join(dir, 'asset')), { code: 'ENOENT' });
});

test('requires a regular state directory', async (t) => {
  const dir = await workspace(t);
  const other = await workspace(t);
  await fs.symlink(other, path.join(dir, '.qentimart'), process.platform === 'win32' ? 'junction' : 'dir');
  const client = fixtureClient(t, 'https://example.org', []);
  await assert.rejects(client.sync(dir, { version: '1.0.0' }), { code: 'EUNSAFE' });
  assert.deepEqual(await fs.readdir(other), []);
});
