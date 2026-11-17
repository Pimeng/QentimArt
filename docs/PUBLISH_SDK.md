# SDK 发布指南

说明如何通过 GitHub Actions 自动把 Python / Node.js SDK 发布到 PyPI 与 npm

两个 SDK 各自独立发版，发布流程依赖 Trusted Publishing（OIDC 可信发布），CI 运行时通过 GitHub 的 OIDC 向包管理源换取临时凭据，仓库里不保存任何长期 token

- [工作方式](#工作方式)
- [一次性配置](#一次性配置)
- [日常发版](#日常发版)
- [常见问题](#常见问题)

## 工作方式

| SDK | 触发 tag | 工作流 | 目标源 |
|---|---|---|---|
| Python | `python-sdk-v<版本>` | [`publish-python-sdk.yml`](../.github/workflows/publish-python-sdk.yml) | [PyPI](https://pypi.org) |
| Node.js | `nodejs-sdk-v<版本>` | [`publish-nodejs-sdk.yml`](../.github/workflows/publish-nodejs-sdk.yml) | [npm](https://www.npmjs.com) |

推送匹配的 tag 即触发对应工作流，流程为：校验 tag 版本与包清单版本一致 → 构建产物 → 通过 OIDC 发布。两个 tag 前缀互不影响，只改一个 SDK 时不会误发另一个。

## 一次性配置

首次启用前需在三处完成绑定，之后无需重复。

**GitHub 仓库环境**：在 Settings → Environments 新建两个环境 `pypi` 和 `npm`，名字与工作流中的 `environment` 字段对应。单人维护可不加保护规则；需要人工把关发布时，可在此配置必需审查者。

**PyPI 可信发布者**：登录 PyPI，在项目的 Publishing 页面（或账户的 Publishing → Add a pending publisher）添加 GitHub Actions 可信发布者，按下表填写。

| 字段 | 值 |
|---|---|
| Owner | `Pimeng` |
| Repository name | `QentimArt` |
| Workflow name | `publish-python-sdk.yml` |
| Environment name | `pypi` |

**npm 可信发布者**：登录 npmjs.com，进入 `@qentimart/sdk` 包的 Settings → Trusted Publisher，添加 GitHub Actions 发布者，填写同一仓库与 `publish-nodejs-sdk.yml`、环境 `npm`。首次发布若包尚不存在，需先用 `npm publish --access public` 手动发一个初始版本，再回来绑定可信发布者。

> PyPI 支持为尚未创建的包预先登记 pending publisher，首发即可全自动；npm 需要包已存在才能绑定。

## 日常发版

以 Python SDK 发布 `0.2.0` 为例，Node.js 把前缀换成 `nodejs-sdk-v`、版本号改在 `package.json` 即可。

1. 修改 [`sdk/python/pyproject.toml`](../sdk/python/pyproject.toml) 的 `version` 为 `0.2.0` 并提交
2. 打 tag 并推送：

```bash
git tag python-sdk-v0.2.0 && git push origin python-sdk-v0.2.0
```

工作流会先校验 tag 里的版本号与 `pyproject.toml` 一致，不一致直接失败，避免 tag 与包版本对不上。

## 常见问题

**发布报 OIDC 相关错误**：检查三处绑定的 Owner、仓库名、工作流文件名、环境名是否逐字一致，任一处不符都会被拒绝。

**提示版本号不一致**：tag 去掉前缀后的版本必须与 `pyproject.toml` / `package.json` 里的 `version` 完全相同。

**同一版本号发过一次后无法再发**：PyPI 与 npm 均不允许覆盖已发布版本，即使删除也不能复用，需递增版本号重新发。

**npm 发布成功但包是私有**：scoped 包默认私有，工作流已带 `--access public`，确认 npm 侧包的可见性设置未被改回私有。
