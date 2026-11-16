// 示例：编辑器插件（Cocos Creator / Electron / VS Code 风格）
// 如何集成 AssetSync SDK。
//
//   ASSETSYNC_SERVER=http://127.0.0.1:8080 ASSETSYNC_PROJECT=mygame \
//   ASSETSYNC_TOKEN=... node examples/plugin-example.mjs ./assets
import { AssetSyncClient, SyncConflictError } from '../src/index.js';

const dir = process.argv[2] ?? './assets';
const client = new AssetSyncClient({
  server: process.env.ASSETSYNC_SERVER ?? 'http://127.0.0.1:8080',
  project: process.env.ASSETSYNC_PROJECT ?? 'mygame',
  token: process.env.ASSETSYNC_TOKEN, // 插件使用只读 token 即可
  concurrency: 8,
});

try {
  // 1) 有没有新版本？（开销很小：只需一个小的 JSON 请求）
  const latest = await client.getVersion('latest');
  const status = await client.status(dir);
  console.log(`local: ${status.version ?? '(never synced)'}  server latest: ${latest.version}`);

  if (status.version === latest.version && status.clean) {
    console.log('already up to date');
  } else {
    // 2) 可选：在更新之前向用户展示本次更新的开销。
    const plan = await client.plan(dir);
    console.log(`update will write ${plan.files.created + plan.files.updated} files, download ~${(plan.bytes.estimatedDownload / 1048576).toFixed(1)} MiB`);

    // 3) 同步。默认会还原本地修改过的文件；使用
    //    localChanges: 'keep' 可保留这些文件，使用 'fail' 则改为中止同步。
    const ac = new AbortController(); // 将其绑定到“取消”按钮
    const result = await client.sync(dir, {
      localChanges: 'overwrite',
      signal: ac.signal,
      onProgress: (p) => {
        if (p.phase === 'download') {
          const pct = p.total ? ((p.done / p.total) * 100).toFixed(1) : '100';
          process.stdout.write(`\r${pct}%  ${(p.bytesPerSecond / 1048576).toFixed(1)} MiB/s   `);
        }
      },
    });
    console.log(`\nsynced to ${result.version} in ${(result.elapsedMs / 1000).toFixed(1)}s`);
    console.log(`downloaded ${(result.bytes.downloadedWire / 1048576).toFixed(1)} MiB, reused ${(result.bytes.reusedLocal / 1048576).toFixed(1)} MiB locally`);
    console.log(`disk usage: ${(result.storage.ratio * 100).toFixed(2)}% of the asset size`);
  }

  // 4) 切换到旧的游戏版本也是同样的方式：
  //    await client.sync(dir, { version: '1.0.0' });
} catch (err) {
  if (err instanceof SyncConflictError) {
    console.error('locally modified files:', err.conflicts);
  } else {
    console.error('sync failed:', err.message);
  }
  process.exitCode = 1;
} finally {
  client.close();
}
