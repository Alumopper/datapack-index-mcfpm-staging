# Mcfpm

Mcfpm 是面向 Minecraft Java Edition 函数项目的独立包管理器。它用一个确定性的锁图管理数据包、资源包、MCFPP 库和经过显式信任的 JVM 插件；公开 afox Nexus 优先提供 Mcfpm 制品，Maven Central 作为可信缺失回退，版本语义和依赖解析由 Mcfpm 描述符与解析器负责。

项目当前版本为 `0.1.0-SNAPSHOT`。协议 schema、锁文件 schema 和 CLI JSON schema 均为 v1。

## 快速开始

构建需要 JDK 25，SDK 产物兼容 Java 17：

```text
./gradlew build
./gradlew :cli:installDist
```

Windows 使用 `gradlew.bat`。普通 CLI 分发包需要系统 Java 17+；`:cli:runtimeZip` 为当前操作系统构建带精简 JRE 的分发包。

只消费现有包时不需要先决定组织名、包名或许可证。直接安装依赖会在当前目录创建消费型 `mcfpm.toml`、解析并下载；若当前目录位于 Minecraft 世界或其 `datapacks`/`resourcepacks` 目录中，同一条命令还会立即部署到该世界：

```text
mcfpm install example:shared@^2.0.0
mcfpm tree
```

自动清单使用 `local:unpublished`、`0.0.0-unpublished` 和 `UNLICENSED` 作为草稿发布字段。依赖解析、下载和部署不受影响；执行 `pack --register`、`manifest sign` 或 `publish` 前必须替换这些字段。决定发布时运行 `mcfpm init --id GROUP:NAME --version ... --license ...` 会补全草稿并保留依赖与仓库配置。

`mcfpm.toml` 是直接依赖的唯一声明来源；`install GROUP:NAME@REQUIREMENT...` 会添加或更新这些声明。`mcfpm.lock` 固定完整的传递闭包、实际仓库来源、哈希、features、签名指纹和载荷加载顺序。依赖范围仅接受精确 SemVer、`^`、`~` 和比较器交集；prerelease 必须被范围明确请求。

## 安装上下文

`mcfpm install` 从当前目录向上选择最近的合法上下文。非 JSON 模式会在 stderr 显示解析、获取、校验和部署阶段进度：

- 项目（`mcfpm.toml`）：解析/校验、下载并验证，不写 Minecraft 目录。
- 世界（`level.dat`）：数据包写入世界；资源包优先写入严格推断出的所属实例。
- 实例（`options.txt` 且有 `resourcepacks` 或 `saves`）：安装独立资源包；存在数据包时必须额外传 `--world`。

显式操作使用 `deploy --world ...`、`deploy --instance ...` 和 `rollback`。`--dry-run` 输出检测证据与完整变更计划；世界到实例的全局资源包影响在 JSON、CI 或非交互环境中必须使用 `--yes`。安装使用会话锁、原子替换、内容哈希、备份和漂移检测。

## 仓库与工具配置

新项目和没有清单的直接安装默认按 `afox -> central` 查询；同一坐标同一版本优先使用 afox。已经显式写入 `default-repository = "central"` 的项目保持只使用 Central。私有/file Maven 仓库必须按 group 显式绑定，绑定始终是单仓库且不回退；锁文件记录实际来源 URI：

```toml
[tool]
consumer-profile = "all"
default-repository = "private"

[tool.repositories]
private = "file:///absolute/path/to/repository/"

[tool.bindings]
"example" = "private"

[tool.options]
"repository.private.username-env" = "NEXUS_USERNAME"
"repository.private.password-env" = "NEXUS_PASSWORD"
"kore.package" = "example.generated"
```

私服凭据只通过上述环境变量名间接引用，不写入 manifest、锁文件或缓存。CLI 也接受可重复的 `--repository-url ID=URI` 和 `--bind GROUP=ID` 覆盖；坐标式 `install` 会把这些覆盖及 `--default-repository` 写回消费清单，后续安装无需重复参数。`mcfpm config` 会显示内置仓库和有效优先级。`mcfpm import github --audit-only` 与 `mcfpm import url HTTPS_URL --audit-only` 会生成确定性的 `.mcfpm-import` 候选，`mcfpm import publish` 只发布冻结字节；消费者仍只访问最终 Maven 制品。

## 模块

- `model`：公开协议模型、SemVer、诊断和 canonical JSON。
- `core`：解析、锁文件、缓存、ZIP、签名、信任、bundle 与 SDK。
- `repository-maven`：Maven Central/private/file 仓库实现。
- `source-github`：GitHub API 来源固定、许可证识别、配方/锁文件和 Minecraft pack 静态检查。
- `publish-nexus`：Nexus Components API 直传、坐标碰撞保护与幂等校验。
- `minecraft-java`：上下文检测、NBT、事务安装与回滚。
- `publish-central`：可复现 Central bundle 和 Publisher Portal 客户端。
- `cli`：完整命令面、schema-v1 JSON 和固定退出码。
- `gradle-plugin`：`moe.afox.mcfpm` 插件及 Kore bindings。
- `mcfpp-adapter`：当前 MCFPP `READ_LIB` 编译阶段的 stream 桥接与可信 JVM 插件边界。
- `private-nexus-e2e`：真实 GitHub Release/commit 的私有 Nexus 上传、解析、校验与离线回归夹具。

更多细节见 [CLI 契约](docs/CLI.md)、[包协议](docs/PROTOCOL.md)、[安装模型](docs/INSTALLATION.md)、[私有 Maven](docs/PRIVATE_MAVEN.md)、[发布流程](docs/PUBLISHING.md) 和 [Kore/MCFPP 集成](docs/INTEGRATIONS.md)。

Apache License 2.0。
