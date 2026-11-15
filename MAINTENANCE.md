# GreedyCraft 2 的 StellarCore 1.5.22 维护分支

仓库：https://github.com/Shiver211/StellarCore/tree/gc2-1.5.22

基线固定为 `47477615e390eed9a55c140e8af57f8cf209da70`，提交时间
`2025-04-30 22:57:48 +08:00`，原版本号 `1.5.22`。维护版本为 `1.5.22-gc2.4`。
后续移植在 `gc2-1.5.22` 上单独提交，不合并新版 master 的全部改动。

## 已迁移内容

| 内容 | 来源与适配 |
| --- | --- |
| 加载后冻结模型池，清空并收缩底层哈希表 | Gct-Core `42972db0668ebe6bc95c923228200cea63214a6e` 中的 `StellarQuadPoolHelper` 和 4 个 stellarcore mixin；改为星核源码原生实现。保留加载期间去重、冻结后同步返回原对象、异步/延迟提交跳过的行为。 |
| HUD 缓存维度黑名单及 IGI 绘制 | 本地 bansoukou 的 `StellarCore-1.5.22.jar`；对应上游 `af8dd723bb4f64b30763d8d6fd5b29282ec6fe84`。继续读取现有 `HudCachingDimensionBlacklist`。 |
| 无锁小写字符串池 | 上游 `019acc5eace2f09aa6ea2dbfc6a72dc82f5540ca`；使用 `NonBlockingHashMap` 和原子计数。 |
| CTM 纹理完整路径缓存、线程安全元数据缓存 | 上游 v1.6.8 的 `MixinResourceUtil`，文件最后变更为 `1c138ceb2394b3e8702010a5271704840cb1e9b7`；未移植连接规则缓存，资源重载时清理路径缓存。 |
| LibNine 按资源缓存模型类型 | 上游同一提交的 `MixinL9Models`；保留 1.5.22 的资源重载注册实例，并保留跨命名空间识别模型的原实际行为。 |
| 默认资源包类路径索引（gc2.2） | 每次模型加载前一次扫描所有命名空间；仅完整索引的确定未命中跳过 `Class.getResource`。命中保留原加载器与路径校验，OptiFine 资源提供逻辑仍先执行，资源重载/加载结束释放索引，同时关闭存在性探测流。 |
| 特殊资源来源兼容（gc2.3） | 对无法枚举的非 `file:` URL 单独保留原 `URLClassLoader.findResource` 查询，索引未命中时再检查这些来源。修复 Forge `asmgen:/` 和 ZenUtils `jar:` 地址使整个索引回退的问题；加载结束关闭辅助加载器，记录其他禁用原因。 |
| 成功定位后复用 URL（gc2.4） | 默认资源包通过路径校验后，直接打开已定位 URL，避免 `Class.getResourceAsStream` 再次搜索类路径。保留 OptiFine 优先资源流、路径校验异常回退、开流失败返回 null；局部变量不跨调用共享。 |

维护版已删除旧 bansoukou 包中没有对应实现的 `ParallelModelLoaderBlackListPredefined`、
`DirectoryResourcePackIndex` 两个无效配置项。
并行模型黑名单仍使用原有 `ParallelModelLoaderBlackList`。
索引仅作用于默认资源包的类路径查询，覆盖父加载器和 Manifest 附加类路径；
未知加载器、无法读取的 jar 或指向资源的旧式 JarIndex 保留原查询。
gc2.3 的 `complete=true` 表示已覆盖全部查询来源，包括按需查询的 `fallbackSources`，
并非所有来源都被转换成了文件索引。虚拟来源中的新增资源仍由原协议处理器决定。
未加入外部资源包索引、模型磁盘缓存或 HEI 专项优化。

原始文件 SHA-256：

```text
mods/StellarCore-1.5.22.jar
e6235c16ab45ed5497b4664b1eb236abd63cd13ebe4b5d968014478cb7b59403
bansoukou/StellarCore-1.5.22.jar
551ae038df0eeeacadfc071e481dce3e4185822d0058d6f0e99e2a6d25566592
```

## 构建

使用 JDK 17 运行 Gradle 8.2。字节码版本保持原 1.5.22 的情况：
主体为 Java 8，生成的版本常量类 `Tags` 为 Java 17。
恢复旧构建所需的变更包括 RFG 1.4.2、GTNH HTTPS Maven、取消 Azul 厂商限制，
以及显式使用 UTF-8 处理资源并保留 JSON 转义，避免损坏中文模组描述和换行。
GitHub Actions 同步使用 JDK 17。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat test build --no-daemon --console=plain
# 游戏安装包：build/libs/StellarCore-1.5.22-gc2.4.jar
# 不要安装 -dev、-sources 或 -javadoc 包。
```

本地 `test build` 已通过，共 19 项 JUnit 测试。原有 3 项覆盖模型池生命周期及字符串池；
新增 15 项覆盖索引查询、父加载器、Manifest、目录变更、重建、多版本 jar、
未知来源回退、并行查询、探测流关闭、原版资源索引回退及动态 ResourceLocation，
以及虚拟资源增删、关闭后回退、带目录或类文件后缀的 `jar:` URL。
gc2.4 新增直接打开已定位 URL、IOException 返回 null 的测试；原版及 OptiFine G5
字节码的 Mixin 注入离线验证通过，确认成功分支替换一次、异常回退分支仍保留。
CTM 1.0.2.31、LibNine 1.2.2 的目标类和方法已核对。
gc2.2 已核对原版和 OptiFine G5 补丁后的 `getResourceStream` 注入点与 SRG 映射。
gc2.2 已启动实测，但日志为 `complete=false`，主要索引优化未启用。
gc2.3 使用从实际游戏抓取的类路径和 Forge 原协议处理器离线复现为
665 个 jar、166648 条资源、`complete=true`。随后一次启动实测同样为 `complete=true`，
构建耗时 503ms；尚待纹理目检及 F3+T 资源重载验收。

## 安装与回退

在游戏退出后操作。备份放到 `minecraft` 外，避免被模组或补丁加载器再次扫描。

gc2.3 升级 gc2.4：备份并移出旧 gc2.3 jar，安装 gc2.4 jar，配置不变。
本次与 GCTCore 的 GeckoLib 骨架索引、NuclearCraft 资源合并优化组合测试。

从已经部署的 gc2.2 升级到 gc2.3 时，只备份并移走 `StellarCore-1.5.22-gc2.2.jar`，
安装 `StellarCore-1.5.22-gc2.3.jar`。GCT 和配置无需再次改动，仍由现有
`ResourceExistStateCache` 开关控制。回退时恢复此前备份的 jar。

以下为从原始 1.5.22 首次迁移的步骤：

1. 备份 `mods/StellarCore-1.5.22.jar`、`mods/GCT-Core-1.0.42.jar`、
   `bansoukou/StellarCore-1.5.22.jar`、`config/GCTCore/GCTMixin.cfg` 和 `config/stellar_core.cfg`。
2. 将旧 mods 星核包移出实例，放入 `StellarCore-1.5.22-gc2.3.jar`，
   同时替换本批清理星核 mixin 后的 `GCT-Core-1.0.42.jar`。
3. 将旧 bansoukou 星核包移出实例；其中的类覆盖已迁入源码。
4. 清理 `GCTMixin.cfg` 的旧 `Freeze StellarCore Quad Pools After Load` 配置项，
   以及 `stellar_core.cfg` 中上述两个无效配置项。
5. 在星核 `general.performance.forge` 下使用 `B:FreezeQuadPoolsAfterLoad=true`，
   保留现有 HUD 维度黑名单和其他配置。

本批 GCT-Core 已移除星核 mixin、注册项、辅助类、旧开关和编译桩。
若仅更换星核而继续使用未清理的旧 GCT-Core，则须将旧冻结开关设为 `false`。

回退时移走新星核 jar，并恢复以上 5 个备份文件。

## 验证启动收益

在相同 Java、启动参数、模组和资源包下比较旧版与维护版，各采样至少 3 次。
检查主菜单、HUD 黑名单维度和 F3+T 资源重载后的纹理，再对比相同阶段的 Spark 数据。
原采样 `https://spark.lucko.me/HOlEFs9rzM` 的 MOD_LOAD 为 253.643 秒，
并非完整启动时间；不同线程的采样耗时不能直接相加作为节省时间。

gc2.1 对照采样为 `https://spark.lucko.me/aO5IN27fJI`，覆盖 255.408 秒。
gc2.2 实测 `https://spark.lucko.me/rCuiutU5yF` 覆盖 252.040 秒，
但索引回退，不能把比 gc2.1 少的 3.368 秒归因于索引收益。
gc2.3 实测 `https://spark.lucko.me/4x5K9doQpO` 覆盖 256.462 秒。
默认资源包查询的 Client thread 采样耗时从 9.760 降到 4.700 秒，
主资源重载从 35.496 降到 31.632 秒；其他初始化阶段变慢，整段采样没有缩短。
两轮之间另有 GCT jar 与配置修改，因此不是严格的单变量对照。
日志 `StellarCore-ClasspathAssetIndex` 输出资源数、jar 数、构建时间和 `complete`；
gc2.3 另列出 `fallbackSources`。只有已覆盖全部来源且确认资源不存在时才跳过原查询。
