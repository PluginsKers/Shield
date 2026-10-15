# shield

Paper、Folia、Purpur 行为采集插件，将事件发送到 Shield 服务进行分析和人工复查，不执行封禁。

## 运行要求

插件使用 Paper 1.21.7 API 与 Java 21 字节码。Paper、Folia、Purpur 共用同一个 JAR；其他分支须保留对应 Paper API 与区域调度语义。原生 Spigot/Bukkit、Fabric、Forge 和代理端不能直接加载。

以下组合已检查插件加载、采集初始化、`/shield reload` 和正常停服：

| 服务端 | Minecraft | Java | ProtocolLib |
| --- | --- | --- | --- |
| Paper | 1.21.7 | 21 | 5.4.0 |
| Folia | 1.21.8 | 21 | 5.4.0 |
| Paper、Folia、Purpur | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de（Paper 构建） |

其他 Minecraft 版本与服务端分支尚未逐项验证。ProtocolLib 必须明确支持目标 Minecraft 版本；其当前 Paper 开发构建要求 26.2 或更新版本，不能用于较旧服务端。

上述检查不覆盖真实玩家的跨区域移动、战斗、警告确认或线上推理接口。安装前须在目标服务端验证这些流程。

## 构建

构建使用 JDK 25，运行环境见上表。依赖声明见 [build.gradle.kts](build.gradle.kts)。

```bash
./gradlew build
```

Windows 使用 `.\gradlew.bat build`，产物为 `build/libs/shield-<version>.jar`。

## 安装

1. 安装与服务端版本兼容的 ProtocolLib，再安装 shield。
2. 在 [Shield 后台](https://shield.i5res.com/dashboard#servers) 创建服务器，将接入配置保存到 `plugins/shield/config.yml`。
3. 重启服务端，执行 `/shield` 查看状态。

## 玩家行动

`config.yml` 的 `interventions` 配置本地行动策略，修改后执行 `/shield reload` 生效。仅配置 `server-key` 时使用默认值。

- `enabled`：行动总开关，默认开启。
- `warning.enabled`：警告开关，默认开启。`cooldown-seconds` 默认 1800，范围 300 至 86400；`message` 为警告内容。
- `kick.enabled`：踢出开关，默认关闭。`minimum-prior-warnings` 为至少成功确认的警告次数，默认 1，范围 1 至 100；`cooldown-seconds` 默认 3600，范围 60 至 604800；`message` 为踢出原因。
- 两种 `message` 均须为 1 至 256 字符的单行文字。未知配置项、错误类型与越界值会阻止插件启动。

开启踢出仍须收到推理端已授权的踢出指令，并通过本地警告次数、冷却与有效期检查。警告在玩家关闭提示后确认；踢出回执表示已调用踢出，不确认客户端断线。行动结果通过现有事件队列持久化、重试并上报推理端，包含本地未启用、前置条件或冷却限制、玩家离线、指令过期及结果无效。

## 配置重载

`/shield reload` 可由控制台或拥有 `shield.admin` 权限的玩家执行，支持 Tab 补全。读取与校验整份配置成功后一次性替换行动策略，文件缺失、YAML 错误或配置无效时保留原配置。

尚未开始执行的行动按新策略检查；已显示的警告继续原有确认与回执流程。重载保留事件队列、冷却记录、警告次数与回执，不重复执行历史指令。

`server-key` 绑定服务器身份与现有上报队列，变更后需要重启服务器；此时整个重载会被拒绝，行动配置也保持原值。

- [配置说明](src/main/resources/config.yml)

## License

Copyright (C) 2026 PluginsKers.

Project code is licensed under [AGPL-3.0-only](LICENSE), with a Minecraft server and ProtocolLib linking permission. See [NOTICE](NOTICE) for that permission and third-party notices included in the JAR.
