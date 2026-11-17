// 示例：Yunzai-Bot V3 插件的"素材更新"（参考 phi-plugin 的"曲绘库"更新）
//
// 用法：把本文件放进 plugins/<你的插件>/apps/ 下，Yunzai 会自动加载导出的类。
// 依赖：在插件目录执行 `npm install @qentimart/sdk`（零第三方依赖，Node ≥ 18）。
//
// phi-plugin 这类插件靠 `git clone` / `git pull` 更新曲绘库：仓库里每张曲绘都是
// 二进制，改一张就要整文件重传；git 还会在本地再留一份完整历史（.git），下游磁盘
// 直接翻倍。QentimArt 换了套做法——块级增量，只拉变化的块，本地不留对象缓存，
// 稳态占用 ≈ 素材本身 + 极小的 .qentimart/ 元数据。
//
// 曲绘库是公开分发的（任何用户都能拉），所以这里用【静态模式】：把曲绘构建成静态树
// 托管到 Cloudflare Pages / 对象存储 / CDN，客户端只要一个公开 baseUrl，全程只读、
// 无需 token。走鉴权服务端时改用动态模式即可（见 makeClient 下方注释）。

import plugin from '../../../lib/plugins/plugin.js'
import { QentimArtClient, SyncConflictError } from '@qentimart/sdk'

// 曲绘库的公开分发地址与本地存放目录。实际插件里这两项应来自插件配置
// （对应 phi-plugin 的 downIllUrl / 曲绘目录），这里用常量示意。
const ILL_BASE_URL = 'https://phi-ill.pages.dev'
const ILL_DIR = './plugins/phi-plugin/resources/illustration'

const MiB = 1024 * 1024
const mib = (n) => (n / MiB).toFixed(1)

function makeClient () {
  // 静态模式：只需公开根地址，无需 server / project / token。
  return new QentimArtClient({ baseUrl: ILL_BASE_URL, concurrency: 16 })

  // 动态（鉴权服务端）模式改为：
  //   return new QentimArtClient({
  //     server: 'http://assets.internal:8080',
  //     project: 'phi-illustration',
  //     token: cfg.readToken, // 只读 token 即可
  //     concurrency: 16,
  //   })
  // 其余 sync / plan / status 的用法完全一致。
}

export class IllustrationUpdate extends plugin {
  constructor () {
    super({
      name: '曲绘库更新',
      dsc: '基于 QentimArt 的曲绘库增量更新',
      event: 'message',
      priority: 1000,
      rule: [
        { reg: '^#?(强制)?更新曲绘$', fnc: 'update' },
        { reg: '^#?曲绘版本$', fnc: 'version' }
      ]
    })
  }

  // #更新曲绘 / #强制更新曲绘（强制更新需要主人权限，与 phi-plugin 的 downill 一致）
  async update (e) {
    const force = /强制/.test(e.msg)
    if (force && !e.isMaster) {
      await e.reply('强制更新曲绘仅限主人使用。')
      return true
    }

    const client = makeClient()
    try {
      // 1) 先对比版本——开销极小，只是一个 JSON 请求，别一上来就下载。
      const [latest, status] = await Promise.all([
        client.getVersion('latest'),
        client.status(ILL_DIR)
      ])

      if (status.version === latest.version && status.clean) {
        await e.reply(`曲绘库已是最新：v${latest.version}（${latest.fileCount} 张，${mib(latest.totalSize)} MiB）`)
        return true
      }

      // 2) 预演一次，把"要下多少"先告诉用户（对应 phi-plugin 更新前的提示）。
      //    force → overwrite：还原本地改过的曲绘并清理多余文件；
      //    非 force → keep：保留用户本地改动，只补齐其余文件（更温和的默认）。
      const policy = force ? 'overwrite' : 'keep'
      const plan = await client.plan(ILL_DIR, { localChanges: policy })
      const changed = plan.files.created + plan.files.updated
      const download = plan.bytes.estimatedDownload ?? plan.bytes.downloadedWire
      await e.reply(
        status.version
          ? `发现新版本 v${latest.version}（当前 v${status.version}）：${changed} 张曲绘有变化，约需下载 ${mib(download)} MiB，开始更新……`
          : `首次下载曲绘库 v${latest.version}：${plan.files.total} 张，约 ${mib(download)} MiB，开始下载……`
      )

      // 3) 正式同步。聊天里不能每个块都播报，这里每 3 秒汇报一次进度即可。
      let lastTick = 0
      const result = await client.sync(ILL_DIR, {
        localChanges: policy,
        onProgress: (p) => {
          if (p.phase !== 'download') return
          const now = Date.now()
          if (now - lastTick < 3000) return
          lastTick = now
          const pct = p.total ? ((p.done / p.total) * 100).toFixed(0) : '100'
          e.reply(`更新中… ${pct}%（${mib(p.bytesPerSecond ?? 0)} MiB/s）`)
        }
      })

      // 4) 收尾汇报：真正走线的流量、本地复用量、以及磁盘占用比。
      const reused = result.bytes.reusedLocal
      const lines = [
        `曲绘库已更新到 v${result.version}，用时 ${(result.elapsedMs / 1000).toFixed(1)}s`,
        `实际下载 ${mib(result.bytes.downloadedWire)} MiB` + (reused > 0 ? `，本地复用 ${mib(reused)} MiB` : '')
      ]
      if (result.files.kept.length) {
        lines.push(`已保留 ${result.files.kept.length} 个本地修改的文件（如需覆盖请用"强制更新曲绘"）`)
      }
      if (result.storage) {
        lines.push(`磁盘占用为素材大小的 ${(result.storage.ratio * 100).toFixed(2)}%`)
      }
      await e.reply(lines.join('\n'))
    } catch (err) {
      if (err instanceof SyncConflictError) {
        // 仅在 localChanges:'fail' 时出现；此处默认不会走到，留作参考。
        await e.reply(`检测到本地改动的曲绘，未做改动：\n${err.conflicts.slice(0, 10).join('\n')}`)
      } else {
        await e.reply(`曲绘更新失败：${err.message}`)
      }
    } finally {
      client.close()
    }
    return true
  }

  // #曲绘版本：只看本地/远端版本，不下载。
  async version (e) {
    const client = makeClient()
    try {
      const [latest, status] = await Promise.all([
        client.getVersion('latest'),
        client.status(ILL_DIR)
      ])
      const local = status.version ? `v${status.version}` : '（尚未下载）'
      const tail = status.version === latest.version ? '，已是最新' : '，可更新'
      await e.reply(`曲绘库 本地 ${local} / 最新 v${latest.version}${tail}`)
    } finally {
      client.close()
    }
    return true
  }
}
