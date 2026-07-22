# Shield

Shield 是艾尔大陆后端的数据采集插件。它通过 ProtocolLib 读取客户端入站包，把包转换成跨版本稳定的业务语义，补齐会话、玩家上下文与相邻样本特征，再异步批量写入 PostgreSQL。

插件只负责采集与标记，不作反作弊判定。设计目标是让下游直接按稳定列和 JSONB 载荷训练或分析，减少重复的会话切分、姿态补全、时间差和移动差分预处理。

## 采集管线

```text
ProtocolLib 入站包
  -> 运行时 PacketType 名称解析
  -> 版本兼容语义解码
  -> Velocity 后端玩家上下文快照
  -> 玩家/世界/模式/标签/协议过滤
  -> 动作过滤与共享节流
  -> 会话序列、完整姿态、delta/距离/速度
  -> 非阻塞队列
  -> PostgreSQL 批量写入
```

网络线程不读取 `Player.world`、`location` 或 `gameMode`，也不执行 SQL。上述 Bukkit 状态由加入、切换世界、重生和模式变化事件生成不可变快照。队列满时丢弃新样本并计数，绝不阻塞网络线程。

## 显式数据包白名单

`capture.packets` 是唯一落库白名单。新增到代码目录的包不会在升级后自动开启；当前 ProtocolLib 不支持的名称会跳过，并显示在日志和 `/shield status` 中。

```yaml
capture:
  packets:
    POSITION:
      min-interval-ms: 50
      rate-limit-group: movement
    POSITION_LOOK:
      min-interval-ms: 50
      rate-limit-group: movement
    ATTACK: true
    USE_ENTITY:
      include-actions: [ATTACK, INTERACT, INTERACT_AT]
      raw-fields: true
```

`POSITION`、`POSITION_LOOK`、`LOOK` 和 `GROUND` 可共用 `movement` 节流组，因此同一 50ms 窗口只保留一个移动样本。`include-actions` 非空但包无法解出动作时会拒绝该样本；这是一条有意的 fail-closed 规则。

内置语义目录覆盖：

| 类目 | 数据包 |
|---|---|
| movement | POSITION、POSITION_LOOK、LOOK、GROUND |
| combat | ATTACK、USE_ENTITY、ARM_ANIMATION |
| interaction | USE_ITEM、USE_ITEM_ON、BLOCK_DIG |
| vehicle | VEHICLE_MOVE、STEER_VEHICLE、BOAT_MOVE |
| inventory | HELD_ITEM_SLOT、WINDOW_CLICK、CLOSE_WINDOW、SET_CREATIVE_SLOT、ENCHANT_ITEM、PICK_ITEM、PICK_ITEM_FROM_BLOCK |
| player_state | ENTITY_ACTION、ABILITIES、CLIENT_COMMAND、TELEPORT_ACCEPT、PLAYER_LOADED、CLIENT_TICK_END |
| network | KEEP_ALIVE、PONG |

聊天和自定义载荷不在目录中，避免无意采集聊天隐私或其他插件的任意数据。

## 过滤

`filters.include` 中每个非空维度都必须命中；`filters.exclude` 中任一维度命中即拒绝。空列表表示该维度不限制。

```yaml
filters:
  include:
    uuids: []
    names: []
    worlds: [world]
    game-modes: [SURVIVAL]
    labels: [normal, abnormal]
    protocols: []
  exclude:
    uuids: []
    names: []
    worlds: [resource_world]
    game-modes: [SPECTATOR]
    labels: []
    protocols: []
```

玩家无实质位移、转角或动作超过 `afk-timeout-ms` 后停止落库。为保证挂机恢复准确，Shield 会额外监听必要的活动包，但这些包只有同时出现在白名单时才会写入数据库。

## 跨版本与 ProtocolLib

插件以 Paper 1.21.11 公共 API 和 Java 21 字节码作为编译边界，声明 1.21 API 下限，可部署到 1.21.x–26.1 后端。数据包目录已在 Paper 1.21.11 与 26.1.2 的真实 ProtocolLib 运行时逐项探测。Paper 26.1 需要安装 ProtocolLib 5.5 开发版或更新版本；ProtocolLib 5.4.0 稳定版官方只声明支持 Minecraft 1.21.4–1.21.8，本工程仅用它已发布的公共 API 作为 ProtocolLib 编译边界。

Shield 不直接引用 `BLOCK_PLACE` 等弃用静态常量，而是在启动时从当前 ProtocolLib 的 `Play.Client` 目录按名称解析：

- 1.21.11 及相近版本把攻击、交互合并在 `USE_ENTITY`，Shield 使用 `WrappedEnumEntityUseAction` 解出动作、手和交互坐标。
- 26.1 把攻击拆为 `ATTACK`，`USE_ENTITY` 只保留交互；同时声明两者即可由运行时自动选择支持的形态。
- `VEHICLE_MOVE` 的现代坐标是 `Vec3`，不是三个直接 `double`。
- `STEER_VEHICLE` 的现代载荷是嵌套 `Input` record，旧版则是扁平浮点/布尔字段。Shield 同时支持两种布局。
- `raw-fields: true` 只展开简单标量、枚举和限深 record；未知复杂对象只记录类型，不调用版本不稳定的 `toString()`。

单个字段缺失会得到 `null`，整包解码异常会计数并限频记录堆栈，不再静默吞错。

## Velocity 后端语义

`session_id` 在玩家每次进入一个后端时生成，切服会形成新会话；`server-id` 应为每个后端配置唯一值。UUID、名称、世界和游戏模式来自后端 Bukkit 事件，因此必须正确启用 Velocity modern forwarding，并保护后端不被绕过直连。

`observed_protocol` 特意表示 ProtocolLib 在“当前后端连接”上观测到的协议号。纯 Velocity 场景下它就是连接协议；如果 ViaVersion 只安装在代理端，该值可能是翻译后的后端协议，不能冒充原始客户端版本。需要原始版本时，应在同一采集边界提供 ViaVersion 后端信息或额外的代理转发元数据。

## 数据表与预处理字段

`shield_packet_samples` 的每行包含：

- 会话：`session_id`、`session_sequence`、`session_age_ms`、`since_previous_us`；
- 来源：`server_id`、`proxy_mode`、`observed_protocol`、`server_version`；
- 玩家：UUID、名称、标签、世界、游戏模式；
- 语义：类目、包名、动作、手、目标实体；
- 完整姿态：位置、视角、落地和水平碰撞；
- 派生特征：位置/视角 delta、水平/三维距离、水平/三维速度、`movement_changed`；
- 扩展载荷：稳定键与可选 `raw_fields` 组成的 `payload JSONB`。

`shield_player_labels` 保存 `/shield label` 的人工标记。

本次 schema 升级为 v2。首次启动时若数据库没有 Shield 版本记录，会在事务中删除并重建旧的 `shield_packet_samples`；人工标记表保留。版本写入后，后续重启不会再次清空。

## 命令

权限 `shield.admin`，默认仅 OP：

```text
/shield status
/shield label <玩家|UUID> <normal|abnormal|clear>
/shield reload
```

`status` 显示显式包数量、不支持包、各过滤阶段、解码失败、队列、落库、丢弃和标记统计。`reload` 重建 ProtocolLib 监听器并应用采集规则；数据库和上传参数仍需重启。

## 构建

```text
./gradlew build
```

产物为 `build/libs/Shield-<version>.jar`，已经 shade PostgreSQL 驱动和 HikariCP。运行时需单独安装兼容当前 Minecraft 版本的 ProtocolLib。
