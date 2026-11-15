# GreedyCraft 2 的 StellarCore 1.5.22 维护分支

仓库：https://github.com/Shiver211/StellarCore/tree/gc2-1.5.22

基线固定为 `47477615e390eed9a55c140e8af57f8cf209da70`，提交时间
`2025-04-30 22:57:48 +08:00`，原版本号 `1.5.22`。维护版本为 `1.5.22-gc2.1`。
后续移植在 `gc2-1.5.22` 上单独提交，不合并新版 master 的全部改动。

## 已迁移内容

| 内容 | 来源与适配 |
| --- | --- |
| 加载后冻结模型池，清空并收缩底层哈希表 | Gct-Core `42972db0668ebe6bc95c923228200cea63214a6e` 中的 `StellarQuadPoolHelper` 和 4 个 stellarcore mixin；改为星核源码原生实现。保留加载期间去重、冻结后同步返回原对象、异步/延迟提交跳过的行为。 |
| HUD 缓存维度黑名单及 IGI 绘制 | 本地 bansoukou 的 `StellarCore-1.5.22.jar`；对应上游 `af8dd723bb4f64b30763d8d6fd5b29282ec6fe84`。继续读取现有 `HudCachingDimensionBlacklist`。 |
| 无锁小写字符串池 | 上游 `019acc5eace2f09aa6ea2dbfc6a72dc82f5540ca`；使用 `NonBlockingHashMap` 和原子计数。 |
| CTM 纹理完整路径缓存、线程安全元数据缓存 | 上游 v1.6.8 的 `MixinResourceUtil`，文件最后变更为 `1c138ceb2394b3e8702010a5271704840cb1e9b7`；未移植连接规则缓存，资源重载时清理路径缓存。 |
| LibNine 按资源缓存模型类型 | 上游同一提交的 `MixinL9Models`；保留 1.5.22 的资源重载注册实例，并保留跨命名空间识别模型的原实际行为。 |

旧 bansoukou 包还增加了 `ParallelModelLoaderBlackListPredefined`、
`DirectoryResourcePackIndex` 两个配置字段，但没有对应实现；维护版保留字段及默认值，
没有新增消费逻辑。并行模型黑名单仍使用原有 `ParallelModelLoaderBlackList`。
本轮未加入目录/ZIP 索引、模型磁盘缓存或 HEI 专项优化。

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
# 游戏安装包：build/libs/StellarCore-1.5.22-gc2.1.jar
# 不要安装 -dev、-sources 或 -javadoc 包。
```

本地 `test build` 已通过，3 项 JUnit 测试覆盖加载时去重、冻结后池不再增长、
清空后容量收缩，以及 8 线程字符串池查询和 Locale.ROOT 行为。
CTM 1.0.2.31、LibNine 1.2.2 的目标类和方法已核对。
尚未进行整合包启动、HUD 和资源重载实测，不能据此给出实际节省秒数。

## 安装与回退

在游戏退出后操作。备份放到 `minecraft` 外，避免被模组或补丁加载器再次扫描。

1. 备份 `mods/StellarCore-1.5.22.jar`、`bansoukou/StellarCore-1.5.22.jar`、
   `config/GCTCore/GCTMixin.cfg` 和 `config/stellar_core.cfg`。
2. 将旧 mods 星核包移出实例，放入 `StellarCore-1.5.22-gc2.1.jar`。
3. 将旧 bansoukou 星核包移出实例；其中的类覆盖已迁入源码。
4. 编辑 `config/GCTCore/GCTMixin.cfg`，将旧补丁开关改为：

   ```properties
   B:"Freeze StellarCore Quad Pools After Load"=false
   ```

5. 新版星核会在 `Performance` → `Forge` 下生成默认开启的
   `B:FreezeQuadPoolsAfterLoad=true`。保留现有 HUD 维度黑名单和其他星核配置。
   GCT-Core 继续使用现有版本即可。

回退时移走新 jar，并恢复以上 4 个备份文件。

## 验证启动收益

在相同 Java、启动参数、模组和资源包下比较旧版与维护版，各采样至少 3 次。
检查主菜单、HUD 黑名单维度和 F3+T 资源重载后的纹理，再对比相同阶段的 Spark 数据。
原采样 `https://spark.lucko.me/HOlEFs9rzM` 的 MOD_LOAD 为 253.643 秒，
并非完整启动时间；不同线程的采样耗时不能直接相加作为节省时间。
