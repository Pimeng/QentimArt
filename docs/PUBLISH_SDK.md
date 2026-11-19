# SDK 发布说明

Python 和 Node.js SDK 各自独立发版，通过 GitHub Actions 的 Trusted Publishing（OIDC）直接发到 PyPI 和 npm，仓库里不存长期 token。

## 触发方式

| SDK | 触发 tag | 工作流 | 目标源 |
|---|---|---|---|
| Python | `python-sdk-v<版本>` | [`publish-python-sdk.yml`](../.github/workflows/publish-python-sdk.yml) | [PyPI](https://pypi.org) |
| Node.js | `nodejs-sdk-v<版本>` | [`publish-nodejs-sdk.yml`](../.github/workflows/publish-nodejs-sdk.yml) | [npm](https://www.npmjs.com) |

推对应的 tag 就会触发工作流：检查 tag 版本号和包清单一致 → 构建 → OIDC 发布。两边 tag 前缀分开，只改一个 SDK 不会误发另一个。

## 首次配置

启用前要在三处做好绑定，配一次就行：

### 1. GitHub Environments

在仓库 Settings → Environments 建两个环境：`pypi` 和 `npm`，名字要和工作流里的 `environment` 字段对上。单人维护不用加保护规则，需要人工确认发版再配 required reviewers。

### 2. PyPI Trusted Publisher

登录 PyPI，在项目的 Publishing 页面（首次发包之前在账户 Publishing → Add a pending publisher）添加 GitHub Actions 发布者：

| 字段 | 值 |
|---|---|
| Owner | `Pimeng` |
| Repository name | `QentimArt` |
| Workflow name | `publish-python-sdk.yml` |
| Environment name | `pypi` |

### 3. npm Trusted Publisher

登录 npmjs.com，打开 `@qentimart/sdk` 的 Settings → Trusted Publisher，绑定同一个仓库、工作流 `publish-nodejs-sdk.yml` 和环境 `npm`。

注意 npm 不能给还没建的包预绑可信发布者，首次发包得先本地跑一次 `npm publish --access public`，包建好后再回来绑（PyPI 支持 pending publisher，首发就能全自动）。

## 日常发版

以 Python SDK 发 `0.2.0` 为例（Node.js 把前缀换成 `nodejs-sdk-v`、改 `package.json`）：

1. 把 [`sdk/python/pyproject.toml`](../sdk/python/pyproject.toml) 里的 `version` 改成 `0.2.0` 并提交。
2. 打 tag 并推送：

```bash
git tag python-sdk-v0.2.0 && git push origin python-sdk-v0.2.0
```

工作流会先比对 tag 和清单里的版本号，对不上直接失败。

## 常见问题

- **报 OIDC 相关错误**：逐字核对 Owner、仓库名、工作流文件名和环境名，漏一个字符都会被拒。
- **提示版本号不一致**：tag 去掉前缀后的版本要和 `pyproject.toml` / `package.json` 里的 `version` 完全一致。
- **同一版本号发不了第二次**：PyPI 和 npm 都不允许覆盖已发版本，删了也不能复用，只能升版本号重发。
- **npm 发上去变成私有包**：scoped 包默认私有，工作流里已经带了 `--access public`，检查一下 npm 后台包可见性有没有被改过。
