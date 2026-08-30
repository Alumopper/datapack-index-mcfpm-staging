# 私有 Maven / Nexus

Mcfpm 的 Gradle 发布配置沿用 Katton 的约定。默认基址是 `https://nexus.mcfpp.top`，release 与 snapshot 分别写入 `maven-releases` 和 `maven-snapshots`；版本以 `-SNAPSHOT` 结尾时自动选择 snapshot 仓库。

凭据放在 `~/.gradle/gradle.properties`，也可使用同名环境变量：

```properties
nexusBaseUrl=https://nexus.mcfpp.top
nexusReleasesRepository=maven-releases
nexusSnapshotsRepository=maven-snapshots
nexusUsername=YOUR_USER_TOKEN_NAME
nexusPassword=YOUR_USER_TOKEN_PASSWORD
```

```text
NEXUS_USERNAME
NEXUS_PASSWORD
```

不要把实际凭据提交到项目。所有 `*ToPrivateNexusRepository` 发布任务都会先运行 `validateNexusCredentials`，并且拒绝不安全 HTTP。

## CLI 消费私服

项目只保存环境变量名：

```toml
[tool]
consumer-profile = "minecraft.datapack"
default-repository = "private"

[tool.repositories]
private = "https://nexus.mcfpp.top/repository/maven-snapshots/"

[tool.bindings]
"io.github.example" = "private"

[tool.options]
"repository.private.username-env" = "NEXUS_USERNAME"
"repository.private.password-env" = "NEXUS_PASSWORD"
"repository.private.nexus-api" = "https://nexus.mcfpp.top/service/rest/v1/components"
"repository.private.nexus-name" = "maven-snapshots"
```

用户名与密码必须成对配置。鉴权头只发送给对应仓库同源、同路径下的资源；跨源重定向不会转发调用方请求头。时间戳 Maven snapshot 通过版本目录下的 `maven-metadata.xml` 定位。制品进入 SHA-256 内容缓存后，新 CLI 进程可用 `--offline` 校验，不再访问 snapshot metadata。

标准 `/repository/<name>/` 地址可自动推导 Components API 和仓库名，上例两个 `nexus-*` 选项仅在代理路径或非标准部署时需要。

## 从 GitHub 导入数据包

导入器不运行上游 Gradle、Node、Python 或 shell 脚本，只检查 ZIP。它要求合法 `pack.mcmeta`，并用 `data/`/`assets/` 区分数据包和资源包；两者同时存在、没有 pack 内容或发现多个候选都会停止。源码仓库中 `build/`、`dist/`、`out/`、`release/`、`releases/` 下已有的 ZIP 最多再检查一层，可用 `--subdir` 与 `--nested-zip` 精确选择。

Release 模式固定 release/asset ID、完整 commit、GitHub digest（若 API 提供）、原始 SHA-256 与规范化 payload SHA-256。Archive 模式先把 tag/ref 解析为完整 commit SHA；GitHub 可能改变外层 ZIP 压缩字节，因此持久身份使用 commit 与规范化 payload 哈希。GitHub License API 只识别仓库许可证文件；无法得到明确 SPDX 时必须传 `--license`。

成功上传会写 `mcfpm-import.lock` 和 `build/mcfpm/import-report.json`。相同坐标与相同描述符/载荷是幂等 no-op，release 不允许覆盖。Nexus Components API 拒绝 Maven snapshot 上传，因此该导入通道要求不可变 release SemVer；需要 snapshot 时继续使用 Maven/Gradle 发布客户端。依赖只能来自配方 `[dependencies]` 或重复的 `--dependency GROUP:NAME@REQUIREMENT`，不会从上游构建文件自动推断。

因此导入配方的仓库 ID 应指向 `.../repository/maven-releases/`，不能复用上文的 snapshot 消费示例。

可复用配方示例（CLI 显式值优先于配方，配方优先于推断）：

```toml
schema = 1

[source]
repository = "Elemend/Builders-Wand"
mode = "release-asset"
asset = "builders_wand_2.1.2.zip"
github-token-env = "GITHUB_TOKEN"

[package]
id = "moe.afox.datapacks:builders-wand"
license = "MIT"
type = "minecraft.datapack"
classifier = "datapack"

[repository]
id = "private-releases"

[dependencies]
"example:shared" = { version = "^1.0.0" }
```

tag 与 package version 故意不进入配方；每次调用用 `--tag`，无法从去掉一个前导 `v` 后的 tag 得到 SemVer 时再加 `--version`。精确 release/asset ID、commit、原始/规范化哈希、大小、选中路径与最终坐标写入 lock。

## 真实端到端回归

以下任务会产生远端 snapshot 测试制品，因此不属于普通 `build`：

```text
./gradlew :private-nexus-e2e:runPrivateNexusE2E
./gradlew :private-nexus-e2e:runGitHubImportPrivateNexusE2E
```

它会完成以下闭环：

1. 从 GitHub 下载固定 commit 的归档并校验归档 SHA-256。
2. 提取数据包根目录，生成可复现 ZIP 与 canonical `.mcfpkg` 描述符。
3. 通过 Gradle `maven-publish` 上传到私有 snapshot 仓库。
4. 由 Mcfpm 读取时间戳 snapshot metadata，解析传递依赖，下载并校验载荷。
5. 用相同锁图和内容缓存执行离线复验。

当前隔离坐标是：

- `io.github.jairussw:lifesteal:0.0.0-e2e-SNAPSHOT`，来源 commit `0bdf4d593f7af1c328e3226e526684e6e8405e8d`。
- `io.github.hallettj:no-creeper-griefing:0.0.0-e2e-SNAPSHOT`，来源 commit `79106469e359491f195628360ab50adf03c96965`。

两者均在上游以 MIT 许可发布。LifeSteal 的 E2E 描述符显式依赖 No Creeper Griefing，以同时覆盖传递解析与加载顺序；这只是测试关系，不表示两个上游项目本身存在依赖。

GitHub 说明按 commit ID 下载的归档内容保持稳定，但归档压缩格式可能变化；本夹具额外固定当前传输归档的 SHA-256，以便变化时显式失败并重新审计，而不是静默接受新字节。参考 [GitHub source archives](https://docs.github.com/en/repositories/working-with-files/using-files/downloading-source-code-archives)、[Gradle Maven Publish](https://docs.gradle.org/current/userguide/publishing_maven.html) 与 [Sonatype Maven repositories](https://help.sonatype.com/en/maven-repositories.html)。

新导入闭环使用 `moe.afox.mcfpm.e2e:builders-wand-import`（真实 Release asset）和 `moe.afox.mcfpm.e2e:no-creeper-import`（固定 commit archive），通过 Components API 写入隔离 release `0.0.0-github-import-e2e.1`，再由全新的 Maven repository 客户端解析、在线校验并离线复验。随后它还会从 `runMcfpm` 命令入口执行 JSON/非交互导入，解码生成的 recipe、lock 与 report，并确认重复运行以字节级 `already_present` no-op 收敛。它不依赖 Gradle `maven-publish`。
