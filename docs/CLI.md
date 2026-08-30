# CLI 契约

## 命令面

工程命令：`init`、`add`、`remove`、`update`、`resolve`、`fetch`、`install`、`tree`、`why`。

浏览命令：`search`、`info`、`manifest show`、`artifact fetch`。`search`/`info` 使用 Maven Central 官方 Search REST API；解析与安装不使用搜索索引。

制品命令：`pack`、`verify`、`bundle`、`publish`、`import github`、`import url`、`import publish`。

环境命令：`deploy`、`rollback`、`cache`、`trust`、`auth`、`config`、`doctor`、`completion`。

运行 `mcfpm <command> --help` 查看选项。显式仓库、缓存、离线、JSON、确认与 dry-run 选项在子命令中继承。

消费依赖不要求预先运行 `init`：

```text
mcfpm install example:shared@^2.0.0
mcfpm install example:first@1.0.0 example:second@~2.1.0
```

若当前目录及其父目录没有 `mcfpm.toml`，`install` 会在当前目录创建消费型草稿清单；已有清单则更新同名直接依赖。随后命令立即重新解析、写入锁文件、下载并校验载荷。在普通项目上下文中它不写 Minecraft 目录；从世界根目录或其 `datapacks`/`resourcepacks` 目录调用时，同一条坐标安装命令会在解析后部署到检测出的世界。坐标安装会修改清单，因此不能与 `--dry-run` 同用。非 JSON 模式在 stderr 依次显示解析、获取、校验及可选部署进度；`--json` 保持无进度输出。命令行仓库、binding 和非默认仓库覆盖会写回清单，供后续裸命令复用。

草稿清单的发布字段是 `local:unpublished`、`0.0.0-unpublished` 和 `UNLICENSED`。它们不参与依赖解析；`pack --register`、`manifest sign` 和 `publish` 会列出仍需替换的字段。此时运行 `mcfpm init --id GROUP:NAME --version ... --license ...` 会补全发布身份，同时保留依赖、工具配置、features 和 artifacts；对已有正式身份的项目，`init` 仍拒绝覆盖，除非显式传 `--force`。`pack` 不带 `--register` 时只是生成独立、可复现的载荷 ZIP，不需要发布身份。

GitHub 导入每次只接受一个包。默认读取 Release ZIP；多个 ZIP 资产必须用 `--asset` 精确选择，源码归档则用 `--source archive`。tag/ref 始终在调用时提供，配方不把一次发布版本偷偷固化：

```text
mcfpm import github Elemend/Builders-Wand --tag v2.1.2 --asset builders_wand_2.1.2.zip --repository private-releases --write-recipe mcfpm-import.toml --yes
mcfpm import github --recipe mcfpm-import.toml --tag v2.1.3 --repository private-releases --yes
```

`--dry-run` 会完成下载、来源校验、ZIP 安全检查和 Nexus 坐标预检，但不上传，也不写 report/recipe/lock。`--json`、CI 与无控制台环境的实际上传必须传 `--yes`。

审计与发布也可以拆成两个可复用阶段。普通 HTTPS 导入只允许公开 HTTPS、最多 5 次重定向和 128 MiB 响应；审计生成确定性 `.mcfpm-import`，发布阶段不会重新下载来源：

```text
mcfpm import github OWNER/REPOSITORY --tag v1.2.3 --asset pack.zip \
  --package example:pack --version 1.2.3 --license MIT \
  --audit-only --candidate-output candidate.mcfpm-import
mcfpm import url https://downloads.example/pack.zip \
  --package example:pack --version 1.2.3 --license MIT \
  --audit-only --candidate-output candidate.mcfpm-import
mcfpm --yes import publish --candidate candidate.mcfpm-import \
  --repository-url https://nexus.example/repository/maven-releases/ \
  --repository-name maven-releases --username-env NEXUS_USERNAME \
  --password-env NEXUS_PASSWORD
```

`.mcfpm-import` 只包含 canonical `candidate.json` 和规范化 `payload.zip`，并绑定来源 URL、原始/规范化哈希、大小、坐标、许可证、Minecraft 范围及依赖。发布会重新校验所有字段和字节；同坐标同内容返回 `already_present`，不同内容拒绝覆盖。

## JSON 输出

`--json` 时 stdout 只有一个紧凑 JSON 文档，不输出提示、日志或进度；stderr 只保留不会污染机器输出的运行日志。成功结构：

```json
{"schema":1,"ok":true,"command":"fetch","data":{}}
```

失败结构：

```json
{"schema":1,"ok":false,"exitCode":4,"diagnostics":[{"code":"MCFPM-INTEGRITY-001","severity":"error","message":"...","context":{}}]}
```

稳定退出码：

| 代码 | 类别 |
| ---: | --- |
| 0 | 成功 |
| 2 | 参数、清单或上下文输入错误 |
| 3 | 解析、依赖环或仓库来源错误 |
| 4 | 哈希、ZIP、签名或信任错误 |
| 5 | 网络或离线缓存缺失 |
| 6 | 发布验证或 Central Portal 错误 |
| 7 | 安装、锁、漂移或回滚错误 |
| 70 | 未归类的内部错误（不应由正常输入触发） |

## 信任

可执行载荷默认拒绝。授权必须同时匹配包 ID 和 Ed25519 签名公钥的 SHA-256 指纹：

```text
mcfpm trust add example:compiler-plugin --fingerprint <64位小写SHA-256>
mcfpm trust list
mcfpm trust remove example:compiler-plugin --fingerprint <指纹>
```

授权不向依赖传递，也不允许只有包名或只有签名者的宽泛授权。
