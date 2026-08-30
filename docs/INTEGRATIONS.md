# Gradle、Kore 与 MCFPP

## Gradle 插件

插件 ID 为 `moe.afox.mcfpm`，注册：`mcfpmResolve`、`mcfpmFetch`、`mcfpmVerify`、`mcfpmBundle`、`mcfpmPack`、每载荷 pack 任务、`mcfpmGenerateKoreBindings` 和 `mcfpmPublish`。

```kotlin
plugins {
    kotlin("jvm")
    id("moe.afox.mcfpm")
}

mcfpm {
    manifest.set(layout.projectDirectory.file("mcfpm.toml"))
    lockfile.set(layout.projectDirectory.file("mcfpm.lock"))
    consumerProfile.set("minecraft.datapack")
    payloads.register("main") {
        type.set("minecraft.datapack")
        classifier.set("datapack")
        source.set(tasks.named<Sync>("generateDatapack").map { it.destinationDir })
    }
}
```

配置阶段不访问网络、不写文件。任务只持有 Gradle Properties/files，支持 Configuration Cache；解析、打包和生成任务声明输出，pack/bindings 支持 Build Cache。仓库与 group binding 来自 `mcfpm.toml` 的 `[tool]` 配置。

## Kore

`KoreLockGraphAdapter` 从同一 `ResolvedGraph` 选择 `minecraft.datapack` 载荷，保留依赖顺序并生成不含机器路径的 `KoreDatapacks` 坐标源码。插件把生成目录接入 Kotlin/JVM `main` 或 KMP `commonMain`。Kore 侧继续拥有 `DataPackProvider`、隔离 Worker、bindings 探索/渲染、package prefix、aliases 与 JVM target；解析、下载、缓存、清单和 bundle 不应重复实现。

迁移步骤：删除 Kore 自有依赖解析与下载状态，将依赖写入 `mcfpm.toml`；令生成任务依赖 `mcfpmGenerateKoreBindings`；通过坐标向现有 Provider 提供缓存文件。Minecraft 版本从清单/锁图读取。

## MCFPP

`mcfpp-adapter` 按锁图加载顺序从 ZIP stream 读取 `bin.mclib`、`module.json` 与模块资源，不要求展开目录。`compiler.mcfpp.library` 可通过 `requires` 获取配对资源包；编译输出 registrar 可同时登记数据包、资源包和 MCFPP library。

`CurrentMcfppCompilerAdapter` 对接[当前 MCFPP `kotlin-latest`](https://github.com/MinecraftFunctionPlusPlus/MCFPP) 的 `Project.READ_LIB`、`LibBinReader.readFromStream`、`Module.fromJson` 和 `compile(ProjectConfig)` ABI。它在编译阶段按锁图顺序注入库，模块资源只在受限临时沙箱中存活到编译结束，并在成功或失败后移除 hook、模块与临时文件。上游目前仅提供要求 Java 21 的可变 snapshot，因此这里采用运行时 ABI 检查而不是静态依赖；Mcfpm SDK 仍输出 Java 17 字节码，不兼容的编译器会返回 `MCFPM-MCFPP-001`。

```kotlin
val loaded = McfppDependencyAdapter(fetched).libraries()
if (loaded is McfpmResult.Success) {
    val result = CurrentMcfppCompilerAdapter().compile(projectDir.resolve("mcfpp.json"), loaded.value)
}
```

`jvm.plugin`（包括旧 MNI/JAR）先经过 `ArtifactVerifier` 的包 ID + 签名指纹授权，再进入以平台 ClassLoader 为父级的隔离 `URLClassLoader`。旧 `includes`/`jars` 由 `McfppLegacyConfigInspector` 产生迁移 warning，发布验证必须拒绝未转换配置。
