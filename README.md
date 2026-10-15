<p align="center">
  <img src="assets/brand.svg" alt="Shield" width="294" height="80">
</p>

<p align="center">
  <strong>适用于 Paper、Folia 与 Purpur 的行为采集插件</strong>
</p>

<p align="center">
  <a href="https://github.com/PluginsKers/Shield/releases">下载发行版</a>&nbsp;&nbsp;
  <a href="#开始使用">安装</a>&nbsp;&nbsp;
  <a href="https://shield.i5res.com">Shield 官网</a>&nbsp;&nbsp;
  <a href="https://github.com/PluginsKers/Shield/issues">问题反馈</a>
</p>

Shield 采集玩家移动、战斗与交互事件，异步发送到 Shield 服务进行分析和人工复查。插件按授权指令执行警告或踢出，不执行封禁。

使用前需要在 [Shield 后台](https://shield.i5res.com/dashboard#servers) 创建服务器并获取接入密钥。行为分析依赖远程服务，插件不提供离线推理。

## 开始使用

1. 根据下方[兼容范围](#兼容范围)准备服务端，并安装支持该 Minecraft 版本的 ProtocolLib。
2. 从[发行版](https://github.com/PluginsKers/Shield/releases)下载 `shield-<version>.jar`，放入服务端的 `plugins` 目录。
3. 在 Shield 后台创建服务器，将接入配置保存到 `plugins/shield/config.yml`；目录不存在时先创建。最小配置如下：

   ```yaml
   server-key: "粘贴接入密钥"
   ```

4. 重启服务端，执行 `/shield` 查看插件状态与上报情况。

升级时先备份 `plugins/shield`，替换 JAR 后重启服务端。保留目录中的事件队列与行动记录，避免丢失尚未上报的数据或行动状态。

## 兼容范围

插件基于 Paper 1.21.7 API，使用 Java 21 字节码。Paper、Folia、Purpur 共用同一个 JAR；其他兼容分支须保留对应 Paper API 与区域调度语义。Java 版本还须满足服务端自身要求。

| 服务端 | Minecraft | Java | ProtocolLib |
| --- | --- | --- | --- |
| Paper | 1.21.7 | 21 | 5.4.0 |
| Folia | 1.21.8 | 21 | 5.4.0 |
| Paper | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de（Paper 构建） |
| Folia | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de（Paper 构建） |
| Purpur | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de（Paper 构建） |

以上组合已验证插件加载、采集初始化、配置重载与正常停服，尚未覆盖真实玩家的跨区域移动、战斗、警告确认和线上推理接口。其他 Minecraft 版本与服务端分支尚未逐项验证。

表中的 ProtocolLib Paper 开发构建要求 Minecraft 26.2 或更新版本，不能用于旧版服务端。原生 Spigot/Bukkit、Fabric、Forge 及 Velocity/BungeeCord 代理端不在兼容范围内。

## 配置与命令

完整配置见 [`config.yml`](src/main/resources/config.yml)。仅填写 `server-key` 时采用默认行动策略：警告开启，踢出关闭。开启踢出后，仍须收到远程服务授权的指令，并通过本地警告次数、冷却和有效期检查。

| 命令 | 用途 |
| --- | --- |
| `/shield` | 查看采集、传输与队列状态 |
| `/shield reload` | 校验配置并重载行动策略 |

两条命令均支持控制台，玩家需要 `shield.admin` 权限，默认授予 OP。

<details>
<summary>行动策略与配置校验</summary>

以下配置项位于 `interventions` 下。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 行动总开关 |
| `warning.enabled` | `true` | 允许警告 |
| `warning.cooldown-seconds` | `1800` | 警告冷却，范围 300–86400 秒 |
| `warning.message` | `行为异常` | 玩家看到的警告文字 |
| `kick.enabled` | `false` | 允许踢出 |
| `kick.minimum-prior-warnings` | `1` | 至少成功确认的警告次数，范围 1–100 |
| `kick.cooldown-seconds` | `3600` | 踢出冷却，范围 60–604800 秒 |
| `kick.message` | `异常行为持续发生` | 玩家看到的踢出原因 |

两种 `message` 均须为 1–256 字符的单行文字。未知配置项、错误类型与越界值会阻止插件启动；重载时遇到无效配置则保留原配置。

警告在玩家关闭提示后确认；踢出回执表示已调用踢出，不确认客户端断线。行动结果通过事件队列持久化、重试并上报，包含未启用、前置条件或冷却限制、玩家离线、指令过期及结果无效。

</details>

<details>
<summary>重载与状态保留</summary>

`/shield reload` 读取并校验整份配置后一次性替换行动策略。文件缺失、YAML 错误或配置无效时，现有策略继续生效。

尚未开始执行的行动按新策略检查；已显示的警告继续原有确认与回执流程。重载保留事件队列、冷却记录、警告次数与回执，不重复执行历史指令。

`server-key` 绑定服务器身份与上报队列，变更后必须重启服务器。检测到密钥变更时，整个重载会被拒绝，行动配置也保持原值。

</details>

## 数据与服务

插件通过 HTTPS 向 Shield 服务发送玩家 UUID、名称、世界、游戏模式、位置、行为事件、采集状态和行动回执，不采集聊天内容或自定义载荷。字段定义见 [`PacketSample`](src/main/kotlin/com/eyreland/shield/capture/PacketSample.kt)。

传输失败时，事件会进入本地队列并重试。待发送队列最多保留 4096 批次，磁盘缓存上限为 256 MiB，保留时间为 6 小时。超出容量或保留期限的数据会被清理，队列状态与丢弃计数可通过 `/shield` 查看。

## 从源码构建

构建需要 JDK 25，使用仓库自带的 Gradle Wrapper。运行要求见[兼容范围](#兼容范围)。

```bash
./gradlew build
```

Windows 使用 `.\gradlew.bat build`。产物为 `build/libs/shield-<version>.jar`，可通过 `-Pversion=<version>` 指定版本号。依赖与打包配置见 [`build.gradle.kts`](build.gradle.kts)。

## 参与贡献

问题反馈请附服务端及 Minecraft 版本、Java 版本、ProtocolLib 版本、Shield 版本、复现步骤与相关日志。发布日志前移除接入密钥和玩家个人信息。

欢迎提交修复与兼容性改进。每个 PR 聚焦一个问题，提交和 PR 标题使用 `fix: 中文标题`、`feat: 中文标题` 或 `docs: 中文标题` 等格式。

## 许可证

Copyright (C) 2026 PluginsKers.

项目源码使用 [AGPL-3.0-only](LICENSE)，附 Minecraft 服务端和 ProtocolLib 链接许可。[NOTICE](NOTICE) 包含该许可及随 JAR 分发的第三方组件声明。
