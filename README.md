# Shield

Shield 在 Velocity 后端采集客户端入站包，经 ProtocolLib 转为稳定事件，补齐会话、姿态和相邻样本特征后写入 PostgreSQL。它只生产训练/分析数据，不执行反作弊判定。

## 部署

1. 准备 Paper、对应版本的 ProtocolLib 和 PostgreSQL。
2. 执行 `./gradlew build`，部署 `build/libs/Shield-<version>.jar`。
3. 在 `config.yml` 填写数据库；每个后端设置唯一的 `capture.server-id`。
4. 启用 Velocity modern forwarding，并禁止绕过代理直连后端。
5. 配置采集白名单和过滤器，重启后用 `/shield status` 检查失败与丢弃计数。

> 不迁移旧数据。Shield schema 版本缺失或不一致时，直接删除并重建样本表和人工标签表；相同版本重启不会清空。

## 配置契约

`capture.packets` 是唯一落库白名单：

```yaml
capture:
  server-id: survival-1
  proxy-mode: velocity
  afk-timeout-ms: 180000
  filters:
    include:
      worlds: [world]
      game-modes: [SURVIVAL]
    exclude:
      labels: []
      protocols: []
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

- `include`：所有非空维度都要命中；`exclude`：任一维度命中即拒绝。
- 同一玩家、同一 `rate-limit-group` 共享最小采样间隔。
- `include-actions` 无法求值时拒绝；`raw-fields` 仅展开有界简单结构。
- `afk-timeout-ms` 到期后停止落库，但继续观察恢复活动所需的包。
- 队列满时丢弃新样本，不阻塞网络线程。

内置目录覆盖 movement、combat、interaction、vehicle、inventory、player_state、network 共 28 类包；完整名称见 [`PacketCatalog.kt`](src/main/kotlin/com/eyreland/shield/capture/PacketCatalog.kt)。聊天和自定义载荷不采集。

## 数据契约

主表：`shield_packet_samples`。

| 维度 | 字段 |
|---|---|
| 会话 | `session_id`、`session_sequence`、`session_age_ms`、`since_previous_us` |
| 来源 | `server_id`、`proxy_mode`、`observed_protocol`、`server_version` |
| 玩家 | `player_uuid`、`player_name`、`label`、`world`、`game_mode` |
| 事件 | `category`、`packet_type`、`action`、`hand`、`target_entity_id` |
| 姿态 | `x/y/z`、`yaw/pitch`、`on_ground`、`horizontal_collision` |
| 特征 | `delta_*`、`distance`、`horizontal_distance`、`speed_bps`、`horizontal_speed_bps`、`movement_changed` |
| 扩展 | `payload JSONB` |

建模时：

- 以 `session_id` 分组、`session_sequence` 排序；切换后端即新会话。
- delta、距离和速度相对上一条实际选中样本计算。
- 未携带字段或无前序基线时为 `NULL`，不要与数值零混淆。
- `label` 是采集时快照；`/shield label` 只影响后续样本，当前标签另存于 `shield_player_labels`。
- `observed_protocol` 是后端所见协议；若 ViaVersion 只在代理转换，它不代表原始客户端版本。
- 序列缺口可能来自队列丢弃或批量写入失败，应结合 `/shield status` 做质量控制。

## 兼容与运维

- 1.21.11 从 `USE_ENTITY` 解攻击；26.1 使用独立 `ATTACK`。两者同时配置，由运行时选择。
- 现代车辆坐标为 `Vec3`，现代输入为嵌套 `Input` record；旧扁平布局同时兼容。
- 已用 ProtocolLib 5.5 开发版验证 Paper 1.21.11（27/27）和 Paper 26.1.2（28/28）；插件字节码目标为 Java 21。

```text
/shield status
/shield label <玩家|UUID> <normal|abnormal|clear>
/shield reload
```

权限为 `shield.admin`。`reload` 只更新采集策略；数据库和上传参数需要重启。
