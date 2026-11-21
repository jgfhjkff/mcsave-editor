# 我的世界存档编辑器（mcsave-editor）

直接读写手机里的《我的世界》基岩版（Bedrock）存档，改完回游戏就能生效。支持**网易版（加密库）**与**国际基岩版（标准库）**。

- 纯 Java、全代码自绘界面（没有 layout XML、没有 support/appcompat 依赖），产物是**单 dex**
- 只重写存档里的 `db/` 与 `level.dat`，其余文件（资源包、图标等）原样搬运
- 每次写回前自动备份到 `/sdcard/NM存档备份`，失败自动回滚

## 功能

| 模块 | 能做什么 |
| --- | --- |
| 实体清理 | 整体清理 / 按类别（船、生物、掉落物、经验球、其它）/ 指定实体 ID；可同步清空区块实体索引 |
| 世界参数 | 游戏模式、难度、作弊指令、PVP、死亡不掉落、昼夜循环 |
| 时间与天气 | 固定到早晨/正午/黄昏/夜晚/午夜或自定义 tick；晴 / 雨 / 雷雨 + 持续时长 |
| 出生点与种子 | `SpawnX/Y/Z`、`RandomSeed` |
| 游戏规则 | 10 项常用规则逐项开关，另有「生存纯净 / 创造自由」预设 |
| 玩家 | 坐标、手持物品；清空旧玩家数据（换账号） |
| 背包 | 36 格总览，单格整段 NBT 编辑、附魔（id / lvl / modEnchant，上限 32767）、显示名与介绍 |
| 方块 | 按坐标改单个方块，可带方块状态，缺失子区块时自动按空气新建 |
| 存档管理 | 列表/详情、写入前自动备份、整目录原样备份、原样导出 zip、查看 `world_icon.jpeg` |
| 外观 | 浅色 / 深色主题，切换带过场动画 |

## 安装

- 最新 APK 见本仓库 [Releases](../../releases)；CI 也会在每次提交后产出 `apk` 构建产物（Actions → Artifacts）
- 需要「所有文件访问权限」（Android 11+ 的 `MANAGE_EXTERNAL_STORAGE`），否则读不到 `minecraftWorlds`

## 编译

环境：JDK 17、Android SDK（compileSdk 33）、Gradle 7.6。

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库没有提交 `gradlew` 与 `local.properties`：前者请用系统 Gradle（或在本地执行 `gradle wrapper` 生成），后者按你的 SDK 路径自行创建。

## 存档位置

应用会扫描这些根目录（深度 3 内找含 `level.dat` 的子目录）：

```
/sdcard/Android/data/com.netease.x19/files/minecraftWorlds
/sdcard/Android/data/com.netease.x19/files/games/...
/sdcard/Android/data/com.netease.mc.mcbox/...
/sdcard/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftworlds
/sdcard/minecraftWorlds
```

国际版支持可以在「设置 → 读取国际基岩版存档」里关掉。

## 已知限制（未实机验证的部分）

- 改方块不会重算高度图与光照，也不会创建箱子/告示牌这类方块实体
- 改世界种子只影响尚未生成的区块
- 附魔书写的是 `tag.ench`，不处理 `StoredEnchantments`
- 下界 / 末地的区块键格式按 `维度 int32` 推断，未实机验证
- 写入前请在游戏内退出该世界（不必关整个游戏）；若游戏仍在后台持有该世界（未真正卸载、网易版缓存/云同步），写入可能被游戏覆盖，稳妥起见可结束进程后再写入

## 代码结构

```
app/src/main/java/com/nmsave/editor/
  MainActivity.java   全部界面与交互（代码构建视图、抽屉、弹窗、各编辑页）
  Editor.java         写入流程编排：解包 → 解密 → 改库 → 加密 → 重打包
  BedrockDb.java      LevelDB 读写（varint / 4 字节两种 WriteBatch 编码）
  Nbt.java            NBT 解析与序列化（小端、2 字节字符串长度）
  NbtJson.java        NBT ↔ JSON（简洁 / 严格带 __type__ 两种）
  LevelDat.java       level.dat 读写（世界参数、规则、出生点、种子、天气）
  PlayerEdit.java     玩家实体（~local_player）读写：坐标、手持、背包
  BlockEdit.java      子区块解析/重建、调色板、位打包与位宽升级
  ItemDisplay.java    物品 display（名称/介绍/不掉落）
  WorldStore.java     扫描世界、备份、导出、图标
  Json.java           轻量 JSON 解析器
```

`tools/` 是开发期用的桌面验证程序（不参与打包），mcsave-editor 的源码包里附带。

## 许可证

MIT License，见 [LICENSE](LICENSE)。

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。
