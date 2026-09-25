# 架构决策记录（ADR）索引

## 什么是 ADR

ADR（Architecture Decision Record）用于记录已经接受的、不可轻易改变的架构决策，包括：

- 背景与问题；
- 决策内容；
- 原因与代价；
- 被拒绝的方案；
- 验收或约束。

## 状态说明

| 状态 | 含义 |
| --- | --- |
| `Proposed` | 正在讨论，尚未接受 |
| `Accepted` | 已接受，当前实现应遵守 |
| `Superseded` | 已被后续 ADR 替代，保留历史原因 |

Accepted ADR 的决策发生变化时，应新增 ADR 并把旧 ADR 标记为 `Superseded`，不能直接改写旧决策。

## 当前 ADR

| 编号 | 标题 | 状态 | 说明 |
| --- | --- | --- | --- |
| [ADR-0001](0001-provider-neutral-runtime.md) | 厂商中立 Agent Runtime 核心契约 | Accepted | Message + ContentBlock、ModelState 分离、Provider 独占厂商协议、Tool 快照、单次 Execution、有限扩展点 |
| [ADR-0002](0002-model-state-lifecycle.md) | ModelState 生命周期所有权与显式会话连续状态重置 | Accepted（实现排期 R2） | Provider 返回完整下一状态或 `null`；Runtime 不推测生命周期；用户显式重置状态并推进 revision |
| [ADR-0003](0003-browser-runtime-guards.md) | Browser Agent Runtime 生产级执行守卫 | Accepted（已实施） | 停止原因/Tool 决策、整批预检、五项执行限额、唯一 Outcome、取消/Deadline 迟到隔离、Tool 错误分类、Provider 契约测试 |
| [ADR-0004](0004-context-compaction.md) | 完整聊天历史与模型工作上下文分离的压缩机制 | Accepted（已实施） | 80% 自动触发、当前模型摘要、近期原始消息、Provider 状态投影、手动入口和失败原子性 |
| [ADR-0005](0005-model-target-routing-and-switching.md) | 部署模型目录、按会话路由与显式切换 | Accepted | YAML 唯一部署目录、统一 Router、会话 current Target 与显式 handoff；模型后台暂缓 |
