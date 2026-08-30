# Minecraft 安装与回滚

## 检测

检测器从当前工作目录沿规范化真实父路径向上查找最近标记：`mcfpm.toml` 为项目，`level.dat` 为世界，`options.txt` 且存在 `resourcepacks` 或 `saves` 为实例。若同一层存在冲突标记则失败；不会扫描用户目录或猜测默认 `.minecraft`。

世界所属实例只接受精确 `<instance>/saves/<world>` 关系。显式 `--project`、`--world` 和 `--instance` 使用相同结构验证，并优先于自动检测。

## 目标策略

- 数据包分别安装到 `<world>/datapacks`，并在 `level.dat` 的 `DataPacks.Enabled` 中按锁图顺序启用。
- 实例资源包分别安装到 `<instance>/resourcepacks`，并更新 `options.txt`。非 Mcfpm 条目的相对顺序保持不变。
- 无实例的独立世界只能接收一个资源包：Minecraft `< 26.1` 使用 `<world>/resources.zip`，`>= 26.1` 使用 `<world>/resourcepacks/resources.zip`。多个资源包在任何写入前失败。
- 目标版本优先使用锁文件；缺失时通过 `level.dat` DataVersion 注册表精确推断。内置白名单来自 Mojang 官方 release client 的 `version.json`，覆盖 1.20–1.21.11、26.1–26.1.2 和 26.2；未知值拒绝区间猜测。

## 事务安全

写世界前必须独占取得 `session.lock`；实例使用持久 `.mcfpm.lock`。解析后的每个路径必须处于已验证根目录内，junction/symlink 不能逃逸。

安装先计算全部变更、确认目标未变化，再为每个受管文件保存原始字节与哈希。文件通过同目录临时文件原子替换。中途失败立即逆序恢复；成功后 `.mcfpm/backups/<transaction>/state.json` 记录事务链。升级移除旧受管载荷，但用户修改后的文件在未使用 `--force` 时不会覆盖或删除。

`rollback` 恢复上一事务的载荷、`level.dat`、`options.txt` 与原启用顺序，并将 latest 指针回退。备份哈希不匹配或目标漂移时失败。
