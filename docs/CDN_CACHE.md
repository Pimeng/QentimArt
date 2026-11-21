# CDN 缓存配置

公开资源可以按 URL 的可变性配置共享缓存：

- 不可变资源：数据块 `chunks/<哈希>` 和带具体版本号的清单 `versions/<v>/...`。URL 和内容一一绑定，发新版只会多出新 URL，旧 URL 永远不变。
- 可变指针：全站只有一个入口会变——静态模式是 `index.json`，动态模式是 `latest` 相关接口。

所以配 CDN 的思路很简单：**不可变资源永久缓存，可变指针短 TTL 或不缓存**。发新版本不用清数据块和历史清单缓存，最多等指针过期或者单独刷一下指针 URL。

## 缓存规则速查

### 动态模式（`qentimart-server`）

接口都在 `/api/v1/projects/{project}/` 下。**要求令牌才能读取的部署**会为成功响应发送 `Cache-Control: private, no-store`。CDN 应对整个 API 前缀绕过缓存，确保每次请求都回源鉴权。若旧版本已部署在 CDN 后，升级时须清除该前缀下已有的缓存，并移除强制缓存规则。由公开读取切换为私有读取时也需要清理旧缓存。

下表适用于允许匿名读取的部署。此时服务端为可长期缓存的响应发送 `Cache-Control: public, max-age=31536000, immutable`。

| 请求 | 会不会变 | 服务端缓存头 | CDN 建议 |
|---|---|---|---|
| `GET …/chunks/{hash}` | 否 | `max-age=31536000, immutable` | 永久缓存，遵循源站 |
| `GET …/versions/{v}/manifest`（具体版本号） | 否 | `max-age=31536000, immutable` | 永久缓存，遵循源站 |
| `GET …/versions/latest/manifest` | 会 | 无 | 不缓存，或 ≤ 30 秒 |
| `GET …/versions/latest`、`GET …/versions` | 会 | 无 | 不缓存，或极短 TTL |
| `GET …/versions/{v}/diff?from=…` | 动态计算 | 无 | 不缓存 |
| `POST …/chunks/fetch`、`…/chunks/missing`、`…/chunks`、`PUT …/versions/{v}`、`DELETE …`、`POST …/gc` | — | — | 不缓存（非 GET 默认不缓存） |

带具体版本号的 `GET …/versions/{v}` 返回的单版本元数据其实也不会变，不过服务端没给它加缓存头，默认不缓存就行；想进一步减源站压力可以自己给它配长 TTL，但千万把 `latest` 排除掉。

### 静态模式（静态目录 / Cloudflare Pages）

`qentimart publish --static` 会在输出目录根下生成 `_headers`，已经写好下面这些规则。托管平台支持 `_headers`（如 Cloudflare Pages）的话不用手动配，其他 CDN 对照表来配：

| 路径 | 会不会变 | `_headers` 规则 | CDN 建议 |
|---|---|---|---|
| `/chunks/*` | 否 | `public, max-age=31536000, immutable` | 永久缓存 |
| `/versions/*`（`manifest.json.gz`、`meta.json`） | 否 | `public, max-age=31536000, immutable` | 永久缓存 |
| `/index.json` | 会（唯一会变的文件） | `public, max-age=30, must-revalidate` | 短 TTL，≤ 30 秒 |

## 通用配置要点

以下配置适用于公开动态接口和公开静态资源。

- 让 CDN 优先遵循源站的 `Cache-Control`（动态服务端响应头或静态 `_headers`），自定义规则只用来补源站没带头的路径。
- `chunks/*` 和带具体版本号的 `versions/<v>/*` 配 365 天或更长，尽量带上 `immutable`。
- `index.json`（静态）或 `latest` 相关接口（动态）单独配 ≤ 30 秒或不缓存，不然下游迟迟看不到新版本。
- 不可变路径都不依赖查询参数，缓存键忽略查询字符串可以避免无关参数打散命中率（`latest`、`diff` 这类带参数的本身不缓存）。
- 动态模式的批量取块走的是 `POST …/chunks/fetch`，默认不走 CDN 缓存；想让 CDN 分担数据块流量，要么走 `GET …/chunks/{hash}`，要么直接上静态模式。

## 腾讯云 EdgeOne 公开资源配置

对照 [EdgeOne 缓存配置概述](https://cloud.tencent.com/document/product/1552/95263) 配几项就行：

| EdgeOne 功能 | 配法 |
|---|---|
| 节点缓存 TTL | 加两条规则：`/chunks/` 和带版本号的 `/versions/` 遵循源站或强制 365 天；`index.json`（动态模式为 `latest`）设 30 秒。规则按从上到下匹配，把 `index.json` / `latest` 放前面，别让 `/versions/*` 抢先命中 |
| 自定义 Cache Key | 对可缓存路径忽略全部 URL 参数，只按路径做缓存键 |
| 浏览器缓存 TTL | 不可变资源配长 TTL，`index.json` / `latest` 保持短或不设 |
| 状态码缓存 / 离线缓存 | 可选；源站偶发抖动时，不可变资源开离线缓存可以继续返回边缘旧副本，反正内容不会变 |
| 清除缓存 | 发新版后一般只需要清 `index.json`（静态）或 `latest` 相关 URL（动态），块和历史清单不用清 |
| 预热缓存 | 可选；发大版本且预期有高峰时，可以预热新版 `manifest` 和热点块 |
| 缓存文件预刷新 | 重资源都是 `immutable` 不过期，不用开 |

## 阿里云 ESA 公开资源配置

对照 [ESA 缓存入门](https://help.aliyun.com/zh/edge-security-acceleration/esa/user-guide/get-started-with-cache)：

| ESA 功能 | 配法 |
|---|---|
| 开发模式 | 调试或灰度时临时开，请求直接回源；调完记得关，不然回源流量会上去 |
| 边缘缓存过期时间 | 用自定义规则：URI 开头为 `/chunks/` 或 `/versions/` 的设长（遵循源站或 365 天）；`index.json` 用 URI 等于 `/index.json` 单独设 30 秒 |
| 浏览器缓存过期时间 | 默认遵循源站 `Cache-Control` 即可 |
| 忽略 / 排序查询字符串 | 对可缓存路径开启 |
| 多级缓存 | 建议开；老版本块访问偏冷但内容不变，多级缓存能挡掉不少回源 |
| 缓存保持 | 建议开；减少冷门历史块被淘汰后再回源 |

ESA 里给不可变资源配长 TTL 的表达式写法（把域名换成自己的）：

```
http.host eq "example.com" and (starts_with(lower(http.request.uri), "/chunks/") or starts_with(lower(http.request.uri), "/versions/"))
```

注意运算符要用「开头为」（`starts_with`），不要用「等于其中任意一个」（`in`）。实际路径是 `/chunks/<前两位>/<哈希>` 和 `/versions/<版本号>/manifest.json.gz`，精确匹配一个都命不中。`index.json` 刚好相反，是单个固定文件，用 `http.request.uri eq "/index.json"` 单独配 30 秒。

静态模式下 `/versions/` 里全是具体版本号、没有 `latest`，整段前缀直接长缓存没问题。

## Cloudflare Pages

静态模式对 Cloudflare Pages 基本零配置，`_headers` 会被自动识别：

```bash
qentimart publish --static -o ./site -project mygame -version 1.1.0 ./Assets
npx wrangler pages deploy ./site
```

`wrangler` 按文件哈希增量上传，没变的数据块不会重传。如果是把 `qentimart-server` 放到 Cloudflare 其他产品后面，照着上面[动态模式速查表](#动态模式qentimart-server)配规则就行，服务端自带的 `Cache-Control` 默认会被遵循。

> **文件数上限**：Cloudflare Pages 免费版单站点最多 20,000 个文件（付费版 100,000）。大项目迭代几版后如果块数快到上限，发布时把 `-chunk-avg` 调大（例如 `4194304`）能把文件数压到四分之一左右，见[快速开始 · 静态模式](QUICKSTART.md#5-静态模式--cloudflare-pages)。

## 发新版本后怎么处理缓存

- 数据块、带版本号的清单和元数据都是新 URL，不用清缓存。
- 只需要管可变指针：如果配了 ≤ 30 秒的短 TTL，等最多半分钟下游就能看到新版，一般不用手动刷；如果配了更长 TTL 或者要立刻生效，手动刷一下 `index.json`（静态）或 `latest` 相关接口（`GET …/versions/latest`、`…/versions/latest/manifest`、`GET …/versions`）即可。

## 排查与避坑

- 下游拉不到新版时，直接 `curl` 一下 `index.json` 或 `latest` 看版本号，对比边缘节点和源站是不是一致。
- 就算缓存层偶发返回了损坏的数据块，客户端写盘前也会重算 SHA-256，校验不过会直接报错重试，不会写坏本地文件。
- 千万别给 `latest` 或 `index.json` 配长缓存；配动态模式规则时要把 `…/versions/latest/manifest` 和 `…/versions/<v>/manifest` 区分开，别一条 `/versions/*` 通配把 `latest` 也缓存一年。
- 忽略查询参数只对不可变路径开就行。如果误给 `?from=`、`?prerelease=1` 这类接口开了缓存又忽略参数，不同请求会串缓存。
