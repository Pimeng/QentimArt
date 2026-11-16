// 端到端测试：Go 服务端 + 发布工具、Node.js SDK（以及可用时的 Python SDK）。
// 验证结果逐字节一致、110% 存储上限、本地改动处理策略、版本降级，
// 并报告吞吐量。
//
//   node tests/e2e.mjs                 # 生成约 400 MiB 的资产
//   E2E_SIZE_MB=4000 node tests/e2e.mjs
//   E2E_SKIP_PYTHON=1 node tests/e2e.mjs
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import httpMod from 'node:http';
import { fileURLToPath } from 'node:url';
import { QentimArtClient, SyncConflictError } from '../sdk/nodejs/src/index.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const WORK = path.join(ROOT, 'tests', '.work');
const SIZE_MB = Number(process.env.E2E_SIZE_MB ?? 400);
const EXT = process.platform === 'win32' ? '.exe' : '';
const SERVER_EXE = path.join(WORK, 'qentimart-server' + EXT); // 服务端
const EXE = path.join(WORK, 'qentimart' + EXT); // 客户端（发布 / 查询）
const PUB = 'e2e-publish-token-0000000001';
const READ = 'e2e-read-token-00000000000001';
const PROJECT = 'e2e';

const MiB = 1 << 20;
const fmt = (n) => `${(n / MiB).toFixed(1)}MiB`;
let failures = 0;
const check = (cond, msg) => {
  console.log(`${cond ? '  ok  ' : '  FAIL'} ${msg}`);
  if (!cond) failures++;
};

// ------------------------------------------------------------ 辅助函数

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 4294967296;
  };
}

function textBlob(size, r) {
  const words = ['texture', 'mesh', 'anim', 'shader', 'prefab', 'scene', 'sprite', 'audio', 'level', 'material', '{"x":', '1.25,', '"name":', '\n'];
  const parts = [];
  let n = 0;
  while (n < size) {
    const w = words[Math.floor(r() * words.length)];
    parts.push(w, ' ');
    n += w.length + 1;
  }
  return Buffer.from(parts.join('')).subarray(0, size);
}

async function writeAsset(p, data) {
  await fs.mkdir(path.dirname(p), { recursive: true });
  await fs.writeFile(p, data);
}

async function treeHashes(dir) {
  const out = new Map();
  const walk = async (abs, rel) => {
    for (const e of await fs.readdir(abs, { withFileTypes: true })) {
      if (!rel && e.name === '.qentimart') continue;
      const r = rel ? `${rel}/${e.name}` : e.name;
      const a = path.join(abs, e.name);
      if (e.isDirectory()) await walk(a, r);
      else out.set(r, createHash('sha256').update(await fs.readFile(a)).digest('hex'));
    }
  };
  await walk(dir, '');
  return out;
}

function sameTree(a, b) {
  if (a.size !== b.size) return `file count ${a.size} != ${b.size}`;
  for (const [k, v] of a) if (b.get(k) !== v) return `mismatch at ${k}`;
  return null;
}

async function dirSize(dir) {
  let n = 0;
  for (const e of await fs.readdir(dir, { withFileTypes: true, recursive: true })) {
    if (e.isFile()) n += (await fs.stat(path.join(e.parentPath ?? e.path, e.name))).size;
  }
  return n;
}

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer().listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', maxBuffer: 64 * MiB, ...opts });
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed:\n${r.stdout}\n${r.stderr}`);
  return r;
}

// ------------------------------------------------------------ 资产生成

async function generate(dir) {
  const r = rng(12345);
  const budget = SIZE_MB * MiB;
  let used = 0;
  // 约 25% 小文件、约 35% 中等文件、约 40% 大文件
  const smallBudget = budget * 0.25;
  let i = 0;
  while (used < smallBudget) {
    const size = 2048 + Math.floor(r() * 120 * 1024);
    const compressible = r() < 0.5;
    const data = compressible ? textBlob(size, r) : randomBytes(size);
    const ext = compressible ? '.json' : '.png';
    await writeAsset(path.join(dir, `ui/group${i % 40}/item${i}${ext}`), data);
    used += size;
    i++;
  }
  let m = 0;
  while (used < budget * 0.6) {
    const size = MiB / 2 + Math.floor(r() * 8 * MiB);
    await writeAsset(path.join(dir, `models/m${m}.fbx`), r() < 0.5 ? textBlob(size, r) : randomBytes(size));
    used += size;
    m++;
  }
  let l = 0;
  while (used < budget) {
    const size = Math.min(budget - used + MiB, 64 * MiB + Math.floor(r() * 64 * MiB));
    await writeAsset(path.join(dir, `bundles/pack${l}.pak`), randomBytes(size));
    used += size;
    l++;
  }
  await writeAsset(path.join(dir, 'empty.txt'), Buffer.alloc(0));
  return { smallFiles: i, mediumFiles: m, largeFiles: l };
}

async function mutate(dir, counts) {
  const r = rng(999);
  // 修改 5% 的小文件，删除 2%，新增 60 个
  for (let i = 0; i < counts.smallFiles; i++) {
    const ext = existsSync(path.join(dir, `ui/group${i % 40}/item${i}.json`)) ? '.json' : '.png';
    const p = path.join(dir, `ui/group${i % 40}/item${i}${ext}`);
    const x = r();
    if (x < 0.05) await fs.writeFile(p, randomBytes(4096 + Math.floor(r() * 30000)));
    else if (x < 0.07) await fs.rm(p);
  }
  for (let i = 0; i < 60; i++) await writeAsset(path.join(dir, `ui/new/n${i}.png`), randomBytes(10000 + Math.floor(r() * 50000)));
  // 在第一个大 pack 的中间插入字节（CDC 能保持其余部分不变）
  const pak = path.join(dir, 'bundles/pack0.pak');
  const buf = await fs.readFile(pak);
  const mid = Math.floor(buf.length / 2);
  await fs.writeFile(pak, Buffer.concat([buf.subarray(0, mid), Buffer.from('<<INSERTED-PATCH-DATA>>'), randomBytes(100000), buf.subarray(mid)]));
  // 移动一个中等文件（纯重命名：预期零下载），并重写另一个
  await fs.mkdir(path.join(dir, 'models/moved'), { recursive: true });
  await fs.rename(path.join(dir, 'models/m0.fbx'), path.join(dir, 'models/moved/m0_renamed.fbx'));
  if (counts.mediumFiles > 1) await fs.writeFile(path.join(dir, 'models/m1.fbx'), randomBytes(3 * MiB));
  // 一个文件变成目录
  await fs.rm(path.join(dir, 'empty.txt'));
  await writeAsset(path.join(dir, 'empty.txt/now-a-dir.txt'), Buffer.from('hello'));
}

// ------------------------------------------------------------ 主流程

async function main() {
  await fs.rm(WORK, { recursive: true, force: true });
  await fs.mkdir(WORK, { recursive: true });

  console.log('building server + client...');
  run('go', ['build', '-o', WORK + path.sep, './cmd/qentimart-server', './cmd/qentimart'], { cwd: ROOT });

  const port = await freePort();
  const server = `http://127.0.0.1:${port}`;
  const dataDir = path.join(WORK, 'server-data');
  await fs.writeFile(
    path.join(WORK, 'server.json'),
    JSON.stringify({
      listen: `127.0.0.1:${port}`,
      storage: { type: 'local', path: dataDir },
      tokens: [
        { name: 'pub', token: PUB, role: 'publish', projects: ['*'] },
        { name: 'read', token: READ, role: 'read', projects: [PROJECT] },
      ],
    }),
  );
  const srv = spawn(SERVER_EXE, ['serve', '-config', path.join(WORK, 'server.json')], { stdio: ['ignore', 'ignore', 'pipe'] });
  let srvLog = '';
  srv.stderr.on('data', (d) => (srvLog += d));
  for (let i = 0; i < 50; i++) {
    try {
      if ((await fetch(`${server}/healthz`)).ok) break;
    } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }

  try {
    const src = path.join(WORK, 'src');
    console.log(`generating ~${SIZE_MB} MiB of assets...`);
    const counts = await generate(src);
    const srcSize = await dirSize(src);
    console.log(`  ${counts.smallFiles} small, ${counts.mediumFiles} medium, ${counts.largeFiles} large files, ${fmt(srcSize)}`);
    const tree1 = await treeHashes(src);

    const site = path.join(WORK, 'site'); // 静态分发树（持久镜像）

    const publish = (version) => {
      const t = Date.now();
      const r = run(EXE, ['publish', '-server', server, '-project', PROJECT, '-token', PUB, '-version', version, src]);
      const res = JSON.parse(r.stdout);
      const secs = (Date.now() - t) / 1000;
      console.log(`  published ${version}: ${res.NewChunks}/${res.Chunks} new chunks, uploaded ${fmt(res.UploadedRaw)} (${fmt(res.UploadedWire)} wire) in ${secs.toFixed(2)}s`);
      return res;
    };
    // 同一 src 状态下，同时构建静态分发树（供后面的静态模式检查）。
    const publishStatic = (version) => run(EXE, ['publish', '--static', '-o', site, '-project', PROJECT, '-version', version, src]);

    console.log('\n== publish');
    publish('1.0.0');
    publishStatic('1.0.0');
    const server1 = await dirSize(dataDir);
    await mutate(src, counts);
    const tree2 = await treeHashes(src);
    const pub2 = publish('1.1.0');
    publishStatic('1.1.0');
    const server2 = await dirSize(dataDir);
    console.log(`  server storage: v1 ${fmt(server1)}, after v1.1.0 ${fmt(server2)} (+${fmt(server2 - server1)})`);
    check(server2 - server1 < pub2.UploadedRaw * 1.05 + MiB, 'server only stores the delta for 1.1.0');
    check(server1 <= srcSize * 1.02, `server storage for first version <= source size (${fmt(server1)} vs ${fmt(srcSize)})`);

    // --------------------------------------------------- Node SDK
    console.log('\n== Node.js SDK');
    const client = new QentimArtClient({ server, project: PROJECT, token: READ, concurrency: 8 });
    const dst = path.join(WORK, 'node-dst');

    let r = await client.sync(dst, { version: '1.0.0' });
    console.log(`  fresh sync 1.0.0: ${fmt(r.bytes.downloadedWire)} in ${(r.elapsedMs / 1000).toFixed(2)}s = ${fmt(r.bytesPerSecond)}/s`);
    check(!sameTree(tree1, await treeHashes(dst)), 'fresh sync is byte-identical to 1.0.0');
    let rep = await QentimArtClient.storageReport(dst);
    check(rep.ratio <= 1.1, `storage ratio after fresh sync ${(rep.ratio * 100).toFixed(3)}% <= 110%`);

    r = await client.sync(dst);
    console.log(`  update -> ${r.version}: downloaded ${fmt(r.bytes.downloadedRaw)}, reused locally ${fmt(r.bytes.reusedLocal)}, ${r.files.updated} updated / ${r.files.created} created / ${r.files.deleted} deleted, ${(r.elapsedMs / 1000).toFixed(2)}s`);
    check(r.version === '1.1.0', 'latest resolves to 1.1.0');
    check(!sameTree(tree2, await treeHashes(dst)), 'incremental update is byte-identical to 1.1.0');
    check(r.bytes.downloadedRaw <= pub2.UploadedRaw + MiB, `update downloads only the delta (${fmt(r.bytes.downloadedRaw)} <= ${fmt(pub2.UploadedRaw)} + 1MiB)`);
    check(r.bytes.reusedLocal > 0, 'unchanged chunks of modified/moved files are copied locally');
    rep = await QentimArtClient.storageReport(dst);
    check(rep.ratio <= 1.1, `storage ratio after update ${(rep.ratio * 100).toFixed(3)}% <= 110%`);

    r = await client.sync(dst);
    check(r.upToDate && r.bytes.downloadedWire === 0, 'second sync is a no-op');

    // 本地改动
    const victim = [...tree2.keys()].find((k) => k.startsWith('ui/'));
    await fs.writeFile(path.join(dst, victim), 'locally edited');
    await writeAsset(path.join(dst, 'ui/untracked-by-user.txt'), Buffer.from('mine'));
    let st = await client.status(dst);
    check(st.modified.includes(victim) && st.untracked.includes('ui/untracked-by-user.txt'), 'status reports modified + untracked files');
    let threw = false;
    try {
      await client.sync(dst, { localChanges: 'fail' });
    } catch (e) {
      threw = e instanceof SyncConflictError && e.conflicts.length === 2;
    }
    check(threw, "localChanges='fail' aborts with both conflicts");
    r = await client.sync(dst, { localChanges: 'keep' });
    check(r.files.kept.length === 2 && (await fs.readFile(path.join(dst, victim), 'utf8')) === 'locally edited', "localChanges='keep' preserves local edits");
    r = await client.sync(dst);
    check(!sameTree(tree2, await treeHashes(dst)), "localChanges='overwrite' (default) restores 1.1.0 and removes extra files");

    // 版本降级
    r = await client.sync(dst, { version: '1.0.0' });
    check(!sameTree(tree1, await treeHashes(dst)), `downgrade to 1.0.0 is byte-identical (downloaded ${fmt(r.bytes.downloadedRaw)}, reused ${fmt(r.bytes.reusedLocal)})`);

    // 接管一个没有状态的预先填充好的目录（例如从同事那里拷贝来的）
    const adopt = path.join(WORK, 'node-adopt');
    await fs.cp(src, adopt, { recursive: true });
    r = await client.sync(adopt, { version: '1.1.0' });
    check(r.bytes.downloadedWire === 0 && r.files.adopted === tree2.size, 'existing identical folder is adopted without downloading');

    // 首次同步安全性：从未同步过的目录中的未知文件不会被删除
    const wrongDir = path.join(WORK, 'node-wrongdir');
    await writeAsset(path.join(wrongDir, 'important/user-file.doc'), Buffer.from('do not delete'));
    r = await client.sync(wrongDir, { version: '1.0.0' });
    check(existsSync(path.join(wrongDir, 'important/user-file.doc')) && r.files.kept.includes('important/user-file.doc'), 'first sync never deletes unknown files (deleteUntrackedOnFirstSync=false)');

    // 吞吐量：以高并发全新同步 latest
    const fresh = path.join(WORK, 'node-fresh');
    const fast = new QentimArtClient({ server, project: PROJECT, token: READ, concurrency: 16 });
    r = await fast.sync(fresh);
    console.log(`  throughput (fresh 1.1.0, concurrency 16): ${fmt(r.bytes.written)} in ${(r.elapsedMs / 1000).toFixed(2)}s = ${fmt(r.bytes.written / (r.elapsedMs / 1000))}/s`);
    fast.close();
    client.close();

    // --------------------------------------------------- Python SDK
    const py = process.env.PYTHON ?? (process.platform === 'win32' ? 'python' : 'python3');
    const pyEnv = { ...process.env, PYTHONPATH: path.join(ROOT, 'sdk', 'python') };
    const expect1 = path.join(WORK, 'tree1.json');
    const expect2 = path.join(WORK, 'tree2.json');
    await fs.writeFile(expect1, JSON.stringify(Object.fromEntries(tree1)));
    await fs.writeFile(expect2, JSON.stringify(Object.fromEntries(tree2)));
    const runPy = (file, args) => {
      const res = spawnSync(py, [file, ...args], { encoding: 'utf8', env: pyEnv, maxBuffer: 64 * MiB });
      process.stdout.write(res.stdout);
      process.stderr.write(res.stderr);
      failures += (res.stdout.match(/^ {2}FAIL/gm) || []).length;
      if (res.status !== 0 && !(res.stdout || '').includes('FAIL')) failures++;
    };
    const pyTest = path.join(ROOT, 'sdk', 'python', 'tests', 'e2e_client.py');
    if (!process.env.E2E_SKIP_PYTHON && existsSync(pyTest)) {
      console.log('\n== Python SDK');
      runPy(pyTest, [server, PROJECT, READ, path.join(WORK, 'py-dst'), expect1, expect2, String(pub2.UploadedRaw)]);
    }

    // --------------------------------------------------- 静态模式（Cloudflare Pages 式）
    console.log('\n== 静态模式');
    let fileCount = 0;
    for (const e of await fs.readdir(site, { recursive: true, withFileTypes: true })) if (e.isFile()) fileCount++;
    const staticSrv = httpMod.createServer((req, res) => {
      const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '');
      const fp = path.join(site, rel);
      if (!fp.startsWith(site)) {
        res.statusCode = 403;
        return res.end('forbidden');
      }
      fs.readFile(fp).then(
        (b) => res.end(b),
        () => {
          res.statusCode = 404;
          res.end('not found');
        },
      );
    });
    await new Promise((r) => staticSrv.listen(0, '127.0.0.1', r));
    const baseUrl = `http://127.0.0.1:${staticSrv.address().port}`;
    try {
      const sclient = new QentimArtClient({ baseUrl, concurrency: 16 });
      const sdst = path.join(WORK, 'static-dst');
      let sr = await sclient.sync(sdst, { version: '1.0.0' });
      console.log(`  静态全新同步 1.0.0: ${fmt(sr.bytes.downloadedWire)} in ${(sr.elapsedMs / 1000).toFixed(2)}s = ${fmt(sr.bytesPerSecond)}/s`);
      check(!sameTree(tree1, await treeHashes(sdst)), '静态模式全新同步逐字节一致 (1.0.0)');
      let srep = await QentimArtClient.storageReport(sdst);
      check(srep.ratio <= 1.1, `静态模式 ratio ${(srep.ratio * 100).toFixed(3)}% <= 110%`);
      sr = await sclient.sync(sdst);
      check(sr.version === '1.1.0' && !sameTree(tree2, await treeHashes(sdst)), '静态模式增量更新逐字节一致 (1.1.0)');
      check(sr.bytes.downloadedRaw <= pub2.UploadedRaw + MiB, `静态模式只下载差异 (${fmt(sr.bytes.downloadedRaw)} <= ${fmt(pub2.UploadedRaw)} + 1MiB)`);
      check(sr.bytes.reusedLocal > 0, '静态模式复用本地已有的块');
      sr = await sclient.sync(sdst);
      check(sr.upToDate && sr.bytes.downloadedWire === 0, '静态模式第二次同步为空操作');
      console.log(`  静态树文件数 ${fileCount}（Cloudflare Pages 免费版上限 20000）`);
      sclient.close();

      // Python 静态检查自己托管 site 目录（独立进程），不受此处 spawnSync 阻塞影响。
      const pyStatic = path.join(ROOT, 'sdk', 'python', 'tests', 'e2e_static.py');
      if (!process.env.E2E_SKIP_PYTHON && existsSync(pyStatic)) {
        runPy(pyStatic, [site, path.join(WORK, 'py-static-dst'), expect1, expect2, String(pub2.UploadedRaw)]);
      }
    } finally {
      staticSrv.close();
    }
  } finally {
    srv.kill();
  }

  console.log(failures ? `\n${failures} check(s) FAILED` : '\nall checks passed');
  if (failures) {
    console.log('--- server log tail ---');
    console.log(srvLog.split('\n').slice(-30).join('\n'));
  }
  process.exit(failures ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
