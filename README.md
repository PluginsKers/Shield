# Shield

Shield 在 Velocity 后端通过 ProtocolLib 捕获客户端入站包，转换为稳定事件后，
异步发送到独立的 Shield-Inference 服务。插件不保存原始事件到 PostgreSQL，
不在捕获线程等待推理。I/O 线程可接收短期玩家干预指令：默认只发送一次低频
警告，踢出必须在推理端和本地配置中分别显式开启；插件不执行封禁。

## 链路

```text
ProtocolLib
  → 显式白名单、过滤与节流
Paper 权威事件与低频玩家心跳
  → 有界内存队列
  → gzip + HMAC 的 shield.events.v2
  → 有界磁盘 spool
  → HTTPS POST /shield/v1/events
  → 在线特征、评分、治理与人工复查
  → 有界 ACK 指令、本地幂等台账与执行回执
```

捕获线程只执行非阻塞 `offer`。压缩、磁盘和网络都在单独 I/O 线程执行。
接口不可用时游戏 fail-open；队列或 spool 满时丢弃最旧观测数据并计数，
绝不阻塞玩家线程。

## 部署

1. 准备 Paper 与对应版本的 ProtocolLib，不再需要 PostgreSQL。
2. 在 `config.yml` 直接填写与 Shield-Inference 相同的 HMAC 密钥：

   ```yaml
   inference:
     hmac-secret: "<至少 32 字符>"
   ```

3. 执行 `./gradlew build`，部署 `build/libs/Shield-<version>.jar`。
4. 每个后端配置唯一的 `capture.server-id`，并启用 Velocity modern forwarding。
5. 重启后执行 `/shield`，确认采集运行且没有失败记录。

默认接口为 `https://api.i5res.com/shield/v1/events`。所有符合显式过滤条件的玩家
进入同一采集路径。`hmac-secret` 非空时优先；留空时从 `hmac-secret-env` 指定的环境变量读取。
带真实密钥的部署配置不得提交到版本库。

旧数据库表不会被读取、写入或自动删除。历史数据的归档与删除是独立操作。

## 传输配置

```yaml
inference:
  enabled: true
  endpoint: "https://api.i5res.com/shield/v1/events"
  key-id: "shield-events-1"
  hmac-secret: "<与 Shield-Inference 一致的至少 32 字符密钥>"
  hmac-secret-env: "SHIELD_INFERENCE_HMAC_SECRET"
  schema-version: "shield.events.v2"

  batch-size: 500
  flush-interval-ms: 1000
  queue-capacity: 20000
  connect-timeout-ms: 3000
  request-timeout-ms: 10000
  retry-initial-ms: 1000
  retry-max-ms: 60000

  spool-directory: "inference-spool"
  spool-max-bytes: 268435456
  spool-max-batches: 4096
  spool-ttl-ms: 21600000
  quarantine-max-batches: 32
```

- `batch_id` 在首次写入 spool 时生成；重试保持不变。
- 每次 HTTP 尝试生成新时间戳、nonce 和 HMAC。
- `429`、网络错误和 `5xx` 指数退避。
- `401/403` 计为认证失败；`422` 计为 schema 失败。对应批次进入有界隔离区，
  不做忙重试。
- pending 和隔离区共用容量上限与 TTL，磁盘占用有明确上界。
- spool 文件权限在支持 POSIX 权限的平台设为仅服务账号可读写。
- `reload` 更新采集与玩家干预策略；接口、密钥、spool 和服务身份变更需重启。

HMAC 只提供身份与完整性。生产接口必须使用 HTTPS；只有显式开启
`allow-insecure-http` 或回环地址才允许 HTTP。

## 玩家干预配置

```yaml
interventions:
  enabled: true
  ledger-retention-hours: 168
  warning:
    enabled: true
    cooldown-seconds: 1800
    prefix: "[!]"
    message: "你当前的行为存在异常"
  kick:
    enabled: false
    minimum-prior-warnings: 1
    cooldown-seconds: 3600
    message: "你当前的行为持续异常"
```

警告显示为红色 `[!]`，后接白色 `你当前的行为存在异常`，不附带处罚。本地默认
30 分钟内不重复提示同一玩家。踢出默认关闭；启用后仍拒绝没有本地成功警告记录的指令。

每个指令先写入 `intervention-ledger.properties` 再调度到玩家所属区域线程。
台账保存待执行状态、结果与冷却信息；相同 `directive_id` 在 HTTP 重试或服务器重启
后只会执行一次。`applied`、关闭、冷却、离线、过期等结果通过 v2 有界队列回传；
回执写入磁盘 spool 后才从待重试台账移除，队列拥塞时由 I/O 执行器重试。接口
故障或响应格式错误不触发玩家动作，也不阻塞游戏线程。

## 捕获配置

`capture.packets` 是唯一发送白名单：

```yaml
capture:
  server-id: survival-1
  proxy-mode: velocity
  afk-timeout-ms: 180000
  heartbeat-interval-ticks: 100
  telemetry-interval-ticks: 100
  filters:
    include:
      worlds: [world]
      game-modes: [SURVIVAL]
    exclude:
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
```

- `include` 的所有非空维度都要命中；`exclude` 任一维度命中即拒绝。
- 可用 `capture.filters.include.uuids` 做显式白名单；未配置时不按玩家抽样。
- 同一玩家、同一 `rate-limit-group` 共享最小采样间隔。
- `include-actions` 无法求值时拒绝；v2 不发送反射生成的 `raw_fields`。
- AFK 到期后停止发送，但继续观察恢复活动所需的包。
- 聊天和自定义载荷不采集。

Paper 的 `PrePlayerAttackEntityEvent` 提供客户端攻击意图与目标；伤害、取消和
击退由服务端事件补充。玩家心跳提供位置、速度、效果、属性、移动环境、Ping、
服务端 tick、TPS 与 MSPT。它们都经过同一非阻塞有界队列，不在游戏线程做推理。

所有线上事件标签固定为 `unlabeled`。真实复查结论只在治理服务写入和审计，
不再由游戏服维护标签数据库。

## 接口契约

主协议为 `shield.events.v2`，滚动升级期间可把 `schema-version` 临时设为
`shield.events.v1`：

- 批次：`batch_id`、`sent_at_ms`、`server_id`、`proxy_mode`、
  `server_version`、`events`、`closed_sessions`。
- 会话：`session_id`、单调 `session_sequence`、`session_age_ms`、
  `since_previous_us` 与基于 `System.nanoTime()` 的 `session_monotonic_us`。
- 玩家：UUID、名称、世界、模式和观测协议。
- 事件：来源、稳定事件类型、分类、包类型、动作、目标身份、姿态、字段新鲜度及
  受限语义 payload。
- 采集证明：每个周期报告符合条件玩家数、漏采玩家 UUID 和投递丢失事实。
- 干预回执：`directive_id`、玩家 UUID、`warn/kick`、处理结果与处理时间。

`shield.events.v1` 不包含上述 v2 字段和采集证明；它只用于旧推理端过渡，不能作为
全量覆盖或权威攻击语义的验收证据。

服务返回 `202` 后，Shield 会先验证不超过 1 MiB、1,000 条指令的 ACK 并把新指令写入本地
台账，再删除 pending 文件。响应丢失时用相同 body 重试，服务端按 `batch_id`
返回相同指令；本地再按 `directive_id` 去重。玩家退出、插件停止和 capture reload
都会发送会话关闭标记。

## 运维

```text
/shield
/shield reload
```

`/shield` 展示采集运行状态、累计发送量、内存和磁盘待发量。本次运行的丢弃与
传输失败使用累计计数；隔离批次和最近 HTTP 失败使用当前快照。

`/shield reload` 原子重载采集策略与 `interventions`。接口、密钥、spool、server-id 或
proxy-mode 等传输配置变更仍需重启服务器，命令反馈会明确提示这一边界。

默认治理 cohort 处于 shadow，当前候选模型也没有线上校准授权，因此不会产生
玩家指令。只有已授权模型、已校准且无漂移的 cohort 才可能产生低频警告；接口
故障永远不会触发玩家动作。踢出还要求模型制品、推理端建议开关和上述本地开关
同时授权；当前候选模型的自动处置硬锁不能被配置覆盖。
