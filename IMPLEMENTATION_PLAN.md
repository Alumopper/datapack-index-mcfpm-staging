# Mcfpm：独立的 mcfunction 包管理器

## 总结

- 新建 `G:\AST\Mcfpm`，远程仓库为 `Alumopper/Mcfpm`，采用 Apache-2.0；Maven group 为 `moe.afox.mcfpm`，CLI 名为 `mcfpm`。
- 使用 Kotlin 2.4/JDK 25 构建，SDK 输出 Java 17 字节码；CLI 发布普通 JVM 包和自带精简 JRE 的 Windows、Linux、macOS 压缩包，并为每个 Release asset 发布 SHA-256 sidecar。
- v1 面向 Minecraft Java Edition，统一管理数据包、资源包、编译器库和显式信任的 JVM 插件。公开 afox Nexus 优先分发 Mcfpm 包，缺失时可信回退到 Maven Central，GitHub 只托管源码和 CLI 发行包。
- `mcfpm.toml` 是依赖声明的唯一来源，`mcfpm.lock` 固定完整解析结果；使用 SemVer 2.0 和 PubGrub，同一依赖图内每个包只选择一个版本。
- `mcfpm install` 根据当前工作目录及其祖先自适应：
  - 项目上下文只解析并缓存。
  - Minecraft 实例上下文安装独立资源包。
  - 世界上下文优先找到所属实例并安装资源包到实例；找不到实例时才使用世界专属资源包。
  - 显式 `--project`、`--instance`、`--world` 或 `--context` 始终覆盖自动检测。

## 包协议与公共 API

- 包 ID 使用小写 `group:name`，版本必须是 SemVer。`mcfpm.toml` 包含包信息、Minecraft 版本范围、依赖、features、生产载荷和工具专属配置。
  - 依赖支持精确版本、`^`、`~` 和比较器范围；禁止 `latest`、非 SemVer Maven 范围和未声明版本。
  - prerelease 只有在依赖明确请求时参与解析。
  - 数据包与资源包通过载荷 `requires` 关系配对，但仍保持独立。
- 每个 Maven 发布由一个规范化 UTF-8 JSON 描述符 `${name}-${version}.mcfpkg` 和若干 classifier 制品组成。描述符记录 schema、坐标、依赖、features、载荷 classifier、SHA-256、大小、Minecraft/编译器范围、可执行标记和来源。
  - 内置类型为 `minecraft.datapack`、`minecraft.resourcepack`、`compiler.mcfpp.library`、`jvm.plugin`。
  - 第三方类型使用反向域名字符串扩展。
  - 载荷独立发布，使不同 consumer 只下载需要的内容。[Gradle 自定义发布文档](https://docs.gradle.org/current/userguide/publishing_customization.html)
- `mcfpm.lock` 使用确定性 TOML，记录解析器版本、精确版本、仓库 URL、描述符和载荷校验和、依赖边、已选 features、签名指纹以及数据包/资源包的底到顶加载顺序。
- 公共 JVM SDK 提供：
  - `PackageId`、`VersionRequirement`、`PackageManifest`、`ArtifactDescriptor`、`ResolvedGraph`、`Diagnostic`。
  - `McfpmClient.resolve/fetch/verify/bundle/install`。
  - `PackageRepository` SPI、`ConsumerProfile` 和 `InstallContextDetector`。
  - SDK 不依赖 Gradle API、不调用 `exitProcess`，所有失败返回稳定错误码。
- 新项目和没有清单的直接安装使用 `afox -> central` 优先级；显式 `default-repository = "central"` 的旧项目保持 Central-only。同一 group 的显式 binding 始终单仓库且不回退，锁文件记录实际来源并按该来源获取。

## 核心、CLI 与发布

- 建立 `model`、`core`、`repository-maven`、`minecraft-java`、`publish-central`、`cli`、`gradle-plugin` 模块：
  - 内容寻址缓存以 SHA-256 为键，下载到临时文件后原子提交；支持离线、ETag、并发锁和损坏缓存自愈。
  - ZIP 统一路径、排序、时间戳、权限和压缩参数；拒绝路径穿越、重复条目、异常展开体积及损坏的 `pack.mcmeta`。
  - Maven 只用于存储和枚举版本；依赖语义由 Mcfpm 描述符和 PubGrub 决定。
- CLI 提供：
  - 工程：`init`、`add`、`remove`、`update`、`resolve`、`fetch`、`install`、`tree`、`why`。
  - 浏览：`search`、`info`、`manifest show`、`artifact fetch`。
  - 制品：`pack`、`verify`、`bundle`、`publish`、`import github`、`import url`、`import publish`。
  - 环境：`deploy`、`rollback`、`cache`、`trust`、`auth`、`config`、`doctor`、`completion`。
- `--json` 时 stdout 只输出 schema v1 JSON，日志写 stderr；退出码固定区分参数、解析、完整性、网络、发布和部署失败。
- GitHub 和公开 HTTPS URL 只作为发布时摄取源：
  - GitHub Release asset、tag/commit archive 和普通 HTTPS ZIP 都经过 128 MiB/5 次重定向、ZIP 安全、`pack.mcmeta`、数据包/资源包识别和 SPDX/再分发审核。
  - `import ... --audit-only` 生成只含 canonical `candidate.json` 与规范化 `payload.zip` 的确定性 `.mcfpm-import`；`import publish` 不重新下载来源，发布前重新校验全部哈希。
  - 相同坐标和内容幂等，不同内容拒绝覆盖；CLI Release 校验和固定在 datapack-index 的审核工作流中。
- 可执行载荷默认拒绝。`mcfpm trust add group:name --fingerprint ...` 只授权指定包和签名指纹；信任不传递给依赖。
- `mcfpm publish` 验证锁文件、许可证、载荷、校验和、签名和可复现性后生成 Central bundle。默认上传并等待验证，只有 `--release` 才执行永久发布。[Central Publisher Portal API](https://central.sonatype.org/publish/publish-portal-api/)
- `bundle` 不合并包，生成独立的数据包、资源包及 `mcfpm-bundle.json`。依赖优先、依赖者随后、请求根最后，同级按包 ID 排序；资源路径重叠只警告。

## 基于当前目录的自适应安装

- `InstallContextDetector` 从当前工作目录逐级向上查找最近的合法上下文，使用规范化真实路径并处理 Windows junction/symlink：
  - `mcfpm.toml`：项目上下文。
  - `level.dat`：世界上下文。
  - `options.txt` 且存在 `resourcepacks` 或 `saves`：Minecraft 实例上下文。
  - 当前目录为 `datapacks`，且父目录含 `level.dat`：世界上下文。
  - 当前目录为 `resourcepacks`：根据父目录的 `level.dat` 或 `options.txt` 区分世界与实例。
- 不扫描全盘、不搜索无关兄弟目录。唯一允许的实例推断是确认世界路径严格符合 `<instance>/saves/<world>`，且 `<instance>` 具有实例标记。
- 同一层级同时出现冲突标记时失败，并要求 `--context project|world|instance`；没有识别到上下文时也失败，不回退到默认 `.minecraft`。
- `mcfpm install` 行为：
  - 项目上下文：解析或校验锁文件并下载到缓存，不生成 bundle、不写 Minecraft 目录。
  - 实例上下文：资源包分别复制到 `<instance>/resourcepacks` 并更新 `options.txt` 的启用顺序；若还包含数据包，必须额外指定 `--world`。
  - 世界上下文：数据包安装到 `<world>/datapacks`；资源包首先尝试推断所属实例。
  - 从 `datapacks`、`resourcepacks` 或项目子目录运行时，与在对应根目录运行结果一致。
- 世界上下文中的资源包策略：
  - 成功识别所属实例：将所有资源包保持独立，安装到实例 `resourcepacks` 并更新全局资源包顺序。
  - 无法识别实例且只有一个资源包：安装为世界专属资源包。
    - Minecraft 26.1 之前使用 `<world>/resources.zip`。
    - Minecraft 26.1 及以后使用 `<world>/resourcepacks/resources.zip`；该路径变更来自 [Minecraft 26.1 官方说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)。
  - 无法识别实例且有多个资源包：失败并要求显式 `--instance`，不自动合并。
  - 目标版本来自锁文件；缺失时可从受支持的世界 DataVersion 映射，未知版本直接失败。
- 从世界上下文改为实例级资源包安装会影响该实例的所有世界：
  - 交互终端显示实例路径和影响范围并请求确认。
  - 非交互、`--json` 或 CI 环境必须提供 `--yes`。
  - `--dry-run` 输出完整检测证据、目标路径、复制列表和顺序变化，不写文件。
- 安装前取得世界 `session.lock`；实例或世界正在使用、路径越界或目标结构未知时拒绝写入。
- 每次安装保存受管文件哈希、原启用列表和原子备份：
  - `rollback` 精确恢复数据包、资源包、`level.dat` 和 `options.txt`。
  - 非 Mcfpm 项目及其顺序保持不变。
  - 用户修改过受管文件时不覆盖或删除，除非显式 `--force`。
- `deploy --instance/--world` 保留为完全显式的部署命令，与自适应 `install` 共用同一安装引擎。

## Gradle、Kore 与 MCFPP 集成

- 发布插件 `moe.afox.mcfpm`，注册 `mcfpmResolve`、`mcfpmFetch`、`mcfpmVerify`、`mcfpmBundle`、`mcfpmPack` 和发布任务。
  - DSL 只配置清单、锁文件、consumer profile 和由 Gradle task 生产的载荷 Provider。
  - 配置阶段不联网、不写输出；任务支持 Configuration Cache、Build Cache 和离线执行。
- 将 Kore 当前原型中的解析、下载、清单、拓扑、缓存和 bundle 逻辑迁入 Mcfpm；Kore 保留：
  - `DataPackProvider`、隔离 Worker、bindings 探索与渲染、Kotlin/JVM/KMP source-set 接入。
  - `koreDatapack` 中的 Provider、package prefix、JVM target 和 aliases；Minecraft 版本与依赖改从 `mcfpm.toml` 读取。
  - 为完整数据包图生成无本机路径的 bindings 和 `KoreDatapacks`。
  - 删除未发布的 Gradle 数据包依赖及 external DSL；旧任务名可作为委托任务保留。
- 为[当前 MCFPP 编译器](https://github.com/MinecraftFunctionPlusPlus/MCFPP)增加 SDK 适配：
  - 默认读取与 `mcfpp.json` 同目录的 `mcfpm.toml`。
  - `compiler.mcfpp.library` 包装 `bin.mclib`、`module.json` 和模块资源，并通过 stream API 载入。
  - 编译输出可同时注册数据包、资源包和 MCFPP library。
  - MNI/JAR 映射为 `jvm.plugin`，通过信任检查后才加入隔离 ClassLoader。
  - 旧 `includes`/`jars` 保留一个迁移周期，发布前必须转为受校验载荷。

## 测试与验收

- 单元测试覆盖 SemVer/PubGrub、冲突链、依赖环、canonical JSON/TOML、校验和、ZIP 复现、外部去重、artifact classifier、信任、pack.mcmeta 和加载顺序。
- 使用临时 Maven 仓库构建包含数据包、资源包和 MCFPP library 的 `A → B → C`，验证 consumer profile、离线缓存和删除外部源后的构建。
- 自适应目录测试建立以下矩阵：
  - 项目根及项目子目录。
  - 实例根及实例 `resourcepacks`。
  - 世界根、世界 `datapacks`、26.1+ 世界 `resourcepacks`。
  - `<instance>/saves/<world>` 与无法归属实例的独立世界。
  - 单资源包、多资源包、资源包加数据包、冲突上下文和无上下文。
- 验证世界上下文优先安装资源包到所属实例；找不到实例时单包使用正确版本的世界路径，多包必须失败且不产生部分写入。
- 部署测试覆盖世界占用、交互确认、非交互 `--yes`、dry-run、保留非受管顺序、用户漂移、升级、删除、失败中断和逐字节回滚。
- Gradle TestKit 测试 Kotlin/JVM、KMP、Provider 形式、bindings、配置缓存和连续构建缓存命中。
- MCFPP 集成测试编译三层库导入，验证 `bin.mclib`、模块资源、配对资源包和可执行载荷信任。
- Windows、Linux、macOS CI 校验描述符、锁文件和 ZIP 逐字节一致，并对 CLI 自带 JRE 包做跨目录 smoke test。
- 最终验收：
  - 只声明 A 即可解析并离线使用 A/B/C。
  - Kore 和 MCFPP 使用同一锁图，但选择各自需要的载荷。
  - 数据包和资源包保持独立并按 `C、B、A、根` 排序。
  - 在项目、世界、实例及其子目录执行 `install` 均得到既定行为，且不会扫描或误写其他实例。
  - 缺失元数据、哈希错误、环、名称冲突、未知安装上下文和未信任代码均失败；Minecraft 格式范围不兼容只警告。

## 推荐实施顺序

1. 建立仓库、构建约定、协议模型、确定性序列化和包格式测试。
2. 实现 Maven repository、PubGrub、锁文件、内容缓存和 `resolve/fetch/verify`。
3. 实现 CLI 契约、外部制品摄取、打包、Central 发布和安全信任模型。
4. 实现 bundle、自适应目录检测、实例/世界安装、备份和回滚。
5. 实现通用 Gradle 插件并迁移 Kore 原型。
6. 接入 MCFPP SDK，补齐文档、跨平台 CI、离线与端到端验收。
