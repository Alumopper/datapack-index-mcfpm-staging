# Mcfpm 包协议 v1

## 坐标与版本

包 ID 为小写 `group:name`。版本严格遵循 SemVer 2.0.0，包括任意长度数值标识符的正确比较；构建元数据不参与优先级。范围支持精确版本、caret、tilde 和由空格连接的比较器交集。`latest`、缺失版本、部分版本、通配符和 Maven 范围均非法。只有范围自身含 prerelease 时，prerelease 才可成为候选。

## `mcfpm.toml`

清单严格拒绝未知字段，编码为 UTF-8 的确定性 TOML。核心结构：

```toml
schema = 1

[package]
id = "example:adventure"
version = "1.0.0"
license = "Apache-2.0"
minecraft = ">=1.21 <1.22"

[dependencies]
"example:shared" = { version = "^2.0.0", features = ["client"] }

[[artifacts]]
type = "minecraft.datapack"
classifier = "datapack"
extension = "zip"
sha256 = "<64位小写SHA-256>"
size = 1234
requires = ["minecraft.resourcepack:resourcepack"]
```

本地消费型项目可以由 `mcfpm install GROUP:NAME@REQUIREMENT` 自动创建。为保持仓库 `.mcfpkg` schema 的发布字段严格不变，自动清单写入保留值 `local:unpublished`、`0.0.0-unpublished`、`UNLICENSED`；这些值不能用于注册打包、签名或发布。正式仓库描述符始终要求作者提供真实 `package.id`、`package.version` 与 `package.license`。

内建载荷类型为 `minecraft.datapack`、`minecraft.resourcepack`、`compiler.mcfpp.library` 和 `jvm.plugin`；扩展类型必须使用小写带点名称。每个制品由 `(type, classifier)` 唯一确定。外部摄取制品还记录 source kind、URI、不可变版本和再分发许可证。

`[tool]`、`[tool.repositories]`、`[tool.bindings]` 和 `[tool.options]` 保存确定性的工具配置。仓库 binding 精确匹配 group；锁定来源与当前 binding 不同即失败。

## Maven 布局与描述符

每个版本发布一个 canonical JSON 描述符：

```text
<group path>/<name>/<version>/<name>-<version>.mcfpkg
```

载荷使用 Maven classifier：

```text
<name>-<version>-<classifier>.<extension>
```

描述符 JSON 的对象键递归按 Unicode 字符串顺序排列，集合按协议坐标归一化，不含空白。Ed25519 签名覆盖清除 `signature` 值后的归一化描述符；描述符保存算法、公钥、指纹与签名。指纹是 X.509 编码公钥的 SHA-256。

## `mcfpm.lock`

锁文件为确定性 TOML schema v1，记录：解析器版本、目标 Minecraft 版本、直接根、每个包的精确版本与仓库 URL、描述符 SHA-256、已选 features、签名指纹、每个制品的哈希/大小/来源、依赖边以及底到顶载荷顺序。错误解析绝不产生锁文件；Minecraft 格式范围不匹配写为 warning，不改变解析成功状态。

同一图中每个包只选择一个版本。顺序保证依赖在依赖者之前、请求根最后，同级按包 ID 排序。consumer profile 只保留所需载荷以及它们通过 `requires` 声明的配对闭包。

## ZIP 与 bundle

ZIP 文件名只允许相对 `/` 路径，拒绝盘符、反斜杠、`.`/`..`、重复项、symlink、过量条目和异常展开大小。输出按路径排序，时间固定为 1980-01-01，普通文件权限固定为 `0644`，UTF-8、Deflate level 9、禁用 Zip64。Minecraft 包必须含可解析且声明格式的 `pack.mcmeta`。

bundle 不合并 ZIP；每个载荷保持独立并生成 canonical `mcfpm-bundle.json`。资源路径重叠仅产生稳定 warning。
