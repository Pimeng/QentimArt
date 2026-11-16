#!/usr/bin/env node
// assetsync-node：基于 Node.js SDK 的命令行封装。
import { parseArgs } from 'node:util';
import { AssetSyncClient, SyncConflictError } from '../src/index.js';

const HELP = `assetsync-node <command> [options]

命令：
  sync      --dir DIR [--version latest] [--local-changes overwrite|keep|fail] [--prerelease] [--verify-all] [--delete-untracked-on-first-sync]
  plan      --dir DIR [--version latest]          试运行（只计算同步计划）
  status    --dir DIR                             本地相对上次同步版本的改动
  verify    --dir DIR                             重新计算每个文件的哈希
  versions                                        列出服务器上的版本
  report    --dir DIR                             存储额外开销比例

连接：--server URL --project NAME --token TOKEN  （或使用 ASSETSYNC_SERVER / ASSETSYNC_PROJECT / ASSETSYNC_TOKEN）
调优：--concurrency 8
输出：--json  输出机器可读的结果
`;

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    server: { type: 'string', default: process.env.ASSETSYNC_SERVER },
    project: { type: 'string', default: process.env.ASSETSYNC_PROJECT },
    token: { type: 'string', default: process.env.ASSETSYNC_TOKEN },
    dir: { type: 'string' },
    version: { type: 'string', default: 'latest' },
    'local-changes': { type: 'string', default: 'overwrite' },
    prerelease: { type: 'boolean', default: false },
    'verify-all': { type: 'boolean', default: false },
    'delete-untracked-on-first-sync': { type: 'boolean', default: false },
    concurrency: { type: 'string', default: '8' },
    json: { type: 'boolean', default: false },
    help: { type: 'boolean', short: 'h' },
  },
});

const cmd = positionals[0];
if (!cmd || values.help) {
  process.stdout.write(HELP);
  process.exit(cmd ? 0 : 2);
}

const fmt = (n) => {
  const u = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i ? 1 : 0)}${u[i]}`;
};

const need = (k) => {
  if (!values[k]) {
    console.error(`--${k} is required`);
    process.exit(2);
  }
  return values[k];
};

const out = (obj, text) => {
  if (values.json) console.log(JSON.stringify(obj, null, 2));
  else console.log(text ?? JSON.stringify(obj, null, 2));
};

async function main() {
  if (cmd === 'report') {
    const r = await AssetSyncClient.storageReport(need('dir'));
    return out(r, `assets ${fmt(r.assetBytes)}, overhead ${fmt(r.overheadBytes)}, ratio ${(r.ratio * 100).toFixed(3)}%`);
  }
  const client = new AssetSyncClient({ server: need('server'), project: need('project'), token: values.token, concurrency: Number(values.concurrency) });
  try {
    switch (cmd) {
      case 'versions': {
        const v = await client.listVersions();
        return out(v, v.versions.map((x) => `${x.version === v.latest ? '*' : ' '} ${x.version.padEnd(16)} ${fmt(x.totalSize).padStart(10)}  +${fmt(x.deltaBytes).padEnd(10)} ${x.fileCount} files  ${x.createdAt}`).join('\n') || '(no versions)');
      }
      case 'status':
        return out(await client.status(need('dir')));
      case 'verify':
        return out(await client.verify(need('dir')));
      case 'plan':
      case 'sync': {
        let lastLine = 0;
        const res = await client[cmd](need('dir'), {
          version: values.version,
          localChanges: values['local-changes'],
          prerelease: values.prerelease,
          verifyAll: values['verify-all'],
          deleteUntrackedOnFirstSync: values['delete-untracked-on-first-sync'],
          onProgress: values.json
            ? undefined
            : (p) => {
                const now = Date.now();
                if (now - lastLine < 1000 && p.phase !== 'done') return;
                lastLine = now;
                const pct = p.total ? ((p.done / p.total) * 100).toFixed(1) : '0.0';
                process.stderr.write(`  [${p.phase}] ${pct}%  ${fmt(p.done)}/${fmt(p.total)}  ${fmt(p.bytesPerSecond ?? 0)}/s\n`);
              },
        });
        if (values.json) return out(res);
        const f = res.files;
        const b = res.bytes;
        const lines = [
          `${res.dryRun ? 'plan' : 'synced'} ${res.project} ${res.previousVersion ?? '(none)'} -> ${res.version}${res.upToDate ? ' (already up to date)' : ''}`,
          `files: ${f.total} total, ${f.unchanged} unchanged, ${f.adopted} adopted, ${f.created} created, ${f.updated} updated, ${f.deleted} deleted, ${f.kept.length} kept`,
          res.dryRun
            ? `would write ${fmt(b.toWrite)}, estimated download ${fmt(b.estimatedDownload)}`
            : `wrote ${fmt(b.written)}: ${fmt(b.reusedLocal)} reused locally, ${fmt(b.downloadedRaw)} downloaded (${fmt(b.downloadedWire)} on the wire) in ${(res.elapsedMs / 1000).toFixed(2)}s, ${fmt(res.bytesPerSecond ?? 0)}/s`,
        ];
        if (res.storage) lines.push(`storage: assets ${fmt(res.storage.assetBytes)} + overhead ${fmt(res.storage.overheadBytes)} = ${(res.storage.ratio * 100).toFixed(3)}%`);
        return out(res, lines.join('\n'));
      }
      default:
        process.stdout.write(HELP);
        process.exit(2);
    }
  } finally {
    client.close();
  }
}

main().catch((err) => {
  if (err instanceof SyncConflictError) console.error(err.conflicts.join('\n'));
  console.error(`error: ${err.message}`);
  process.exit(1);
});
