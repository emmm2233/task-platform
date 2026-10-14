# 任务撮合平台 · WebSocket 协议规范

> **为什么单独一份**：OpenAPI 描述不了长连接。IM 与实时通知是四端共用的**有状态**通道，
> 消息格式、序号机制、重连补偿、心跳全部必须**与端无关**（§5.15.8）。
> **对应修复**：P2-⑱ —— 原设计只有 HTTP 拉取消息的接口，未定义 WS 协议。

---

## 1. 连接

| 项 | 约定 |
|---|---|
| 地址 | `wss://ws.example.com/api/v1/ws?token=<accessToken>`（⛔ 不用 query 传长期令牌，只传 15 分钟的 Access Token） |
| 子协议 | `Sec-WebSocket-Protocol: dsh-im.v1`（版本协商，便于将来平滑升级） |
| 握手鉴权 | 网关校验令牌；失败直接 `401` 拒绝握手（⛔ 不建连后再发错误） |
| **消息级鉴权** | ⛔ **连接建立 ≠ 授权**。每条业务消息都要校验「发送者是否属于该会话」，不能靠建连时的身份一路通行（§5.8.7） |
| 单账号多端 | 允许（Web + App 同时在线）；同一端多标签页**只保留一条连接**（其余走 `BroadcastChannel`），避免重复推送 |
| 连接数限制 | 小程序全局最多 2 条（微信限制）；App/Web 单账号 ≤5 条 |
| 心跳 | 客户端每 **25s** 发 `PING`，服务端回 `PONG`；**60s** 无心跳服务端主动断开；客户端指数退避重连（1s→2s→4s→…→30s 封顶） |

---

## 2. 帧格式（统一信封）

所有消息都是 JSON 文本帧：

```json
{
  "type": "IM_MESSAGE",
  "msgId": "c-9f2a1b3c-0007",
  "seq": 10237,
  "ts": 1760505600123,
  "data": { }
}
```

| 字段 | 说明 |
|---|---|
| `type` | 消息类型，见 §3 |
| `msgId` | 客户端生成的幂等 ID（`c-` 前缀）或服务端生成（`s-` 前缀）。⛔ 客户端重发时必须复用同一 `msgId` |
| `seq` | **会话内递增序号**（服务端分配，全局单调）。⭐ 离线补偿的唯一依据（§4） |
| `ts` | 服务端毫秒时间戳（⛔ 客户端不得用它做倒计时，倒计时用 `/version` 的 `serverTime` 校准） |
| `data` | 业务负载，按 `type` 不同 |

---

## 3. 消息类型清单

> 与 `sys_event_registry`（V6 迁移）对齐 —— 事件口径以那张表为准。

### 3.1 客户端 → 服务端

| type | data | 幂等键 | 说明 |
|---|---|---|---|
| `PING` | — | — | 心跳 |
| `IM_SEND` | `{sessionId, msgType, content, fileKey?, extra?, clientMsgId}` | `clientMsgId` | 发送消息。命中敏感词会返回 `IM_SEND_ACK.hit=true` |
| `IM_READ` | `{sessionId, maxReadSeq}` | 天然幂等 | 已读回执（跨端同步） |
| `IM_TYPING` | `{sessionId}` | — | 正在输入（可丢弃，不保证送达） |
| `SUBSCRIBE` | `{topics:["im","notify","order"]}` | — | 订阅主题，减少无关推送 |

### 3.2 服务端 → 客户端

| type | data | 触发场景 |
|---|---|---|
| `PONG` | — | 心跳应答 |
| `IM_MESSAGE` | 完整消息体（同 `/im/sessions/{id}/messages` 的 `ImMessage`） | 收到新消息 / 多端同步自己发出的消息 |
| `IM_SEND_ACK` | `{clientMsgId, messageId, seq, hit, riskTag?}` | 发送结果确认。⭐ 客户端凭 `clientMsgId` 把"发送中"气泡置为"已发送"；`hit=true` 时展示"请勿脱离平台交易"提示 |
| `IM_READ_SYNC` | `{sessionId, userId, maxReadSeq}` | 对端（或自己另一台设备）已读 |
| `NOTIFY` | 通知体（同 `/notifications` 的 `Notification`） | 中标 / 交付 / 验收 / 结算 / 工单进展 |
| `TASK_STATE_CHANGED` | `{taskId, taskNo, fromStatus, toStatus, statusName, allowedActions}` | ⭐ 任务状态变更 —— **前端凭 `allowedActions` 重渲染操作按钮，⛔ 不硬编码状态判断** |
| `SUB_ORDER_STATE_CHANGED` | `{subOrderId, fromStatus, toStatus, remainingSeconds}` | 子订单状态变更（含倒计时校正） |
| `CREDIT_CHANGED` | `{scoreAfter, levelAfter, delta, reason}` | 信誉分变动（前端可即时刷新等级徽章） |
| `FORCE_LOGOUT` | `{reason}` | ⭐ 管理员冻结账号 / 权限版本变更 / 异地登录 → 客户端立即清令牌并跳登录页 |
| `SYSTEM_MAINTENANCE` | `{startAt, durationMinutes, message}` | 维护公告 |
| `ERROR` | `{code, message, retMsgId?}` | 处理失败（如权限不足、会话不存在） |

---

## 4. ⭐ 离线补偿（重连后必须做，否则丢消息）

```
客户端重连成功
  → 对每个本地会话，取本地记录的 maxSeq
  → 调用 GET /im/sessions/{id}/messages?afterSeq={maxSeq}&limit=200
  → 循环拉到返回空为止（说明已追平）
  → 同时调用 GET /notifications?unreadOnly=true 补齐通知
  → 完成后才把连接标记为「就绪」
```

| 要点 | 说明 |
|---|---|
| 为什么不用 WS 补 | 重连瞬间的补拉量可能很大（离线几小时），走 HTTP 可分页、可重试、可断点续传 |
| 为什么按 `seq` 而不是时间 | 时间戳在并发写入下可能相同或乱序；**序号单调递增且唯一**（`uk(session_id, seq)`） |
| 去重 | 客户端按 `(sessionId, seq)` 与 `msgId` 双重去重；服务端按 `clientMsgId` 去重 |
| 小程序特例 | ⚠️ 小程序进后台会断连，**回前台必须先补拉再显示**，否则用户会看到"消息跳空" |

---

## 5. 可靠性与顺序

| 项 | 约定 |
|---|---|
| 顺序 | **单会话内严格有序**（按 `seq`）；跨会话不保证 |
| 投递保证 | **至少一次**（at-least-once）→ 因此消费侧必须幂等 |
| 发送失败 | 客户端未收到 `IM_SEND_ACK`（默认 10s 超时）→ 用**同一 `clientMsgId`** 重发；服务端凭 `uk(session_id, client_msg_id)` 去重并回放 ACK |
| 服务端推送失败 | 客户端不在线时消息已落库；由离线补偿兜底，⛔ 不做 WS 层重推队列 |
| 消息不可删 | ⛔ 撤回只改 `recalled` 标记，**原文保留作为争端证据**（§4.3.6 举证依赖它） |

---

## 6. 各端接入差异（协议一致，仅连接方式不同）

| 端 | 建立方式 | 注意 |
|---|---|---|
| Web | `new WebSocket(url, 'dsh-im.v1')` | 多标签页只留一条连接，其余走 `BroadcastChannel` |
| 微信小程序 | `wx.connectSocket` + `onSocketMessage` | ⚠️ 全局最多 2 条连接；**进后台断连**，回前台必须补拉（§4） |
| App | 原生 WS 或 OkHttp WebSocket | 后台被系统杀死 → 靠**厂商 Push 唤醒** → 唤醒后补拉 |
| 桌面端 | 原生 WS | 可长期常驻；系统托盘角标提示未读 |

> ⛔ **业务模块不得感知端** —— 端差异只体现在"如何建立连接"，消息体、序号、心跳间隔全部一致（§5.15.11）。

---

## 7. 安全要求

| 要求 | 说明 |
|---|---|
| 令牌不进 URL 长期化 | 只传 15 分钟 Access Token；`session_key`（小程序）⛔ 绝不下发到前端 |
| 消息级鉴权 | 每条 `IM_SEND` 都校验发送者属于该会话（防越权发消息到他人会话） |
| 敏感词与风控 | 服务端异步检测（含变体与二维码 OCR），命中打 `riskTag` 并可能扣信誉分 |
| 限流 | `IM_SEND` 用户级 30 条/分钟（`sys_rate_limit_rule.RL_IM_SEND`） |
| 日志 | ⛔ 聊天内容不进应用日志（只进消息表）；排障靠 `traceId` |
