# Shield

Shield 在 Velocity 后端通过 ProtocolLib 捕获客户端入站包，转换为稳定事件后，
异步发送到独立的 Shield-Inference 服务。插件不保存原始事件到 PostgreSQL，
不等待推理结果，也不执行踢出或封禁。

## 链路

```text
ProtocolLib
  → 显式白名单 / 过滤 / 节流
  → 玩家 UUID 稳定灰度
  → 有界内存队列
  → gzip + HMAC 的 shield.events.v1
  → 有界磁盘 spool
  → HTTPS POST /v1/events
  → 在线特征、评分、治理与人工复查
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
5. 重启后执行 `/shield status`，检查 HTTP、spool、重试、拒绝和丢弃计数。

默认接口为 `https://api.i5res.com/shield/v1/events`，默认按 UUID 稳定选择 10%
玩家。`hmac-secret` 非空时优先；留空时从 `hmac-secret-env` 指定的环境变量读取。
带真实密钥的部署配置不得提交到版本库。

旧数据库表不会被读取、写入或自动删除。历史数据的归档与删除是独立操作。

## 传输配置

```yaml
inference:
  enabled: true
  endpoint: "https://api.i5res.com/shield/v1/events"
  key-id: "gray-local-20260729"
  hmac-secret: "<与 Shield-Inference 一致的至少 32 字符密钥>"
  hmac-secret-env: "SHIELD_INFERENCE_HMAC_SECRET"

  rollout-percent: 10.0
  rollout-salt: "shield-gray-v1"

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
- `reload` 只更新捕获策略；接口、密钥、spool、灰度比例和服务身份变更需重启。

HMAC 只提供身份与完整性。生产接口必须使用 HTTPS；只有显式开启
`allow-insecure-http` 或回环地址才允许 HTTP。

## 捕获配置

`capture.packets` 是唯一发送白名单：

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

- `include` 的所有非空维度都要命中；`exclude` 任一维度命中即拒绝。
- 可用 `capture.filters.include.uuids` 做显式白名单，再叠加稳定百分比灰度。
- 同一玩家、同一 `rate-limit-group` 共享最小采样间隔。
- `include-actions` 无法求值时拒绝；`raw-fields` 仅展开有界简单结构。
- AFK 到期后停止发送，但继续观察恢复活动所需的包。
- 聊天和自定义载荷不采集。

所有线上事件标签固定为 `unlabeled`。真实复查结论只在治理服务写入和审计，
不再由游戏服维护标签数据库。

## 接口契约

主协议为 `shield.events.v1`：

- 批次：`batch_id`、`sent_at_ms`、`server_id`、`proxy_mode`、
  `server_version`、`events`、`closed_sessions`。
- 会话：`session_id`、单调 `session_sequence`、`session_age_ms`、
  `since_previous_us`。
- 玩家：UUID、名称、世界、模式和观测协议。
- 事件：分类、包类型、动作、姿态及受限 payload。

服务返回 `202` 后才删除 pending 文件。响应丢失时用相同 body 重试，服务端按
`batch_id` 幂等。玩家退出、插件停止和 capture reload 都会发送会话关闭标记。

## 运维

```text
/shield status
/shield reload
```

`status` 展示：

- 捕获接收、入队、灰度过滤、动作/AFK/节流与解码失败；
- 内存队列、pending/隔离 spool、发送批次和事件；
- 重试、429、认证失败、schema 拒绝、网络失败和最后 HTTP 状态。

当前治理服务处于 shadow，模型清单也禁止线上复查裁决。Shield 永远不根据
接口故障或模型分数处罚玩家。
