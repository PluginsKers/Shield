# Shield

艾尔大陆的玩家数据包高密度采集插件。通过 ProtocolLib 捕获客户端数据包，异步批量写入
PostgreSQL，供后续作弊数据分析使用。本阶段只做采集与标记，不实现任何反作弊判定。

## 采集范围

| 类目 | 数据包 | 说明 |
|---|---|---|
| movement | POSITION / POSITION_LOOK / LOOK | 坐标、视角、是否落地，按密度节流 |
| combat | USE_ENTITY / ARM_ANIMATION | 攻击或交互的目标实体、挥手 |
| item | USE_ITEM / BLOCK_PLACE / BLOCK_DIG | 使用物品、放置、挖掘 |
| vehicle | STEER_VEHICLE / VEHICLE_MOVE | 坐骑操控输入与载具移动 |

数据密度由 `capture.movement-min-interval-ms` 控制，默认 50ms（对应客户端每 tick 一个移动包）。
事件型数据包（战斗/物品/坐骑）不节流，全量捕获。

玩家挂机时停止采集，避免站桩产生的重复样本污染数据集。判定按实质活动:移动包的位移或转角超阈值、
或任意战斗/物品/操控动作都算活动，只收到站桩漂移包不算。距上次活动超过 `capture.afk-timeout-ms`
（默认 180000，即 3 分钟）即停采，玩家一恢复移动或动作立刻续采；设为 0 关闭该功能。

## 玩家标记

OP 用 `/shield label` 把玩家标为 normal 或 abnormal，标记会回填到此后每条样本的 `label` 列，
可直接导出带标签的数据集。标记持久化在 `shield_player_labels`，重启后从库加载。

## 命令

权限 `shield.admin`（默认 op）。

```
/shield status                                  采集开关、队列、累计落库/丢弃/失败、标记人数
/shield label <玩家|UUID> <normal|abnormal|clear>  标记玩家
/shield reload                                  重载采集开关、密度与类目
```

## 配置

见 `src/main/resources/config.yml`。`database.password` 必填，留空插件不会启动采集。
`database.init-schema` 首次启动建表。

## 数据表

- `shield_packet_samples`:每条数据包一行，公共列覆盖各类型字段，无关列留 NULL
- `shield_player_labels`:玩家标记

## 构建

```
./gradlew build
```

产物为 `build/libs/Shield-<version>.jar`（shadowJar，已打包 PostgreSQL 驱动与 HikariCP）。
运行需服务器先装好 ProtocolLib。

## 设计要点

- 落库走单线程 `ScheduledExecutorService`（flush 周期任务 + 标记 upsert），不用 Bukkit 调度器，对 Folia 安全。
- 捕获监听器在 Netty 线程:只读包字段、查内存标记、非阻塞入队，不调用需要主线程的 Bukkit API。
- 队列满丢弃新样本并计数，绝不阻塞捕获线程。
- Paper 独立 ClassLoader 下 `DriverManager` 找不到插件内的 PG driver，连接走 `PGSimpleDataSource`，绕开 `DriverManager`。

## 待办

- USE_ENTITY 的 action（攻击/交互/交互坐标）尚未解码，当前只记目标实体 id。
- 样本的 `world` 为 best-effort 读取，跨线程取不到时为 NULL；如需稳定可改为按 join 事件维护映射。
