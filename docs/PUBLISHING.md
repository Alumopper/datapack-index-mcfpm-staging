# 发布到 Maven Central

## 制品准备

1. 用 `pack` 生成确定性 ZIP；外部摄取必须提供 immutable version、原始 SHA-256、原始大小、来源类型和再分发许可证。
2. 更新 `mcfpm.toml` 的 artifact 哈希与大小，生成 canonical `.mcfpkg`，使用 Ed25519 签名描述符。
3. 解析并验证 `mcfpm.lock`，确认所有本地制品字节与描述符完全一致。
4. 为 POM、`.mcfpkg` 和每个 classifier 制品生成 ASCII-armored OpenPGP detached signature。

描述符 Ed25519 key 与 Maven Central OpenPGP key 是两个独立用途。可用 `mcfpm manifest keygen --private-key .mcfpm/keys/descriptor.key --public-key .mcfpm/keys/descriptor.pub` 创建描述符 key，再用 `mcfpm manifest sign --private-key .mcfpm/keys/descriptor.key --public-key .mcfpm/keys/descriptor.pub` 签署当前清单；`.mcfpm/` 已被忽略，私钥不得提交到仓库。

`publish-central` 的 `CentralBundleBuilder` 接受上述字节、POM 元数据和一个实际的 detached-signature verifier。它要求许可证、开发者、SCM、有效描述符签名、所有 classifier、每个基础文件的 OpenPGP 签名和逐字节可复现性；输出包含 `.asc` 及 MD5/SHA-1/SHA-256/SHA-512 sidecars 的标准 Maven 路径 ZIP。

## Portal 上传门禁

CLI 可以从当前项目生成 POM、描述符和 classifier 文件，用本机 `gpg` 的指定 key 生成并再次验证 detached signatures，检查锁图、许可证、载荷哈希和两次构建的字节一致性，然后上传：

```text
set CENTRAL_USERNAME=<Portal token username>
set CENTRAL_PASSWORD=<Portal token password>
mcfpm publish ^
  --artifact datapack=build/mcfpm/payloads/datapack.zip ^
  --display-name "Example Adventure" ^
  --description "Example datapack" ^
  --project-url https://github.com/example/adventure ^
  --scm-url https://github.com/example/adventure.git ^
  --license-name "Apache License 2.0" ^
  --license-url https://www.apache.org/licenses/LICENSE-2.0.txt ^
  --developer example="Example Developer:developer@example.com" ^
  --signing-key <OpenPGP fingerprint>
```

可以把 POM 元数据写入 `[tool.options]` 的 `publish.*` keys；`--prepare-only` 只生成并验证 `build/mcfpm/central-bundle.zip`。如果 bundle 已由 `CentralBundleBuilder` 生成，也可用 `--bundle <zip>` 直接进入结构/可复现性复验与上传。

默认通过 Central Publisher Portal 的 `USER_MANAGED` 上传，轮询直到 `VALIDATED` 后停止，不会永久发布。审核验证结果后才运行：

```text
mcfpm publish --bundle build/mcfpm/central-bundle.zip --name example-adventure-1.0.0 --release
```

`--release` 是唯一触发 Portal deployment release 的开关。Central 坐标不可变，任何修复必须使用新 SemVer。
