# ArgusAC 检测配置解耦与全链路战斗交互伤害拦截重构计划

本方案旨在对 ArgusAC 反作弊的核心配置体系与战斗防御子系统进行深度重构：将所有检测项配置从 `config.yml` 剥离至独立的 `checks.yml`；彻底剔除 KillAura 中的动态战斗抑制（Combat Throttle / 缴械）与武器蓄力清零机制；并引入独立的 `CombatMitigationHandler` 战斗防护拦截器，构建覆盖 Bukkit 伤害事件与底层数据包的双重静默防御闭环。

---

## 用户决策与关键确认 (User Review & Critical Decisions)

> [!IMPORTANT]
> 基于前期交流，以下设计决策已明确并将在此次重构中贯彻执行：

- **配置文件组织**：在插件根目录下生成独立的 `checks.yml` 集中管理全部 14 项检测模块配置，无需兼容旧版 `config.yml` 中的 checks 节点。
- **战斗惩处精简**：完全移除 KillAura 中的战斗抑制（Combat Throttle / 缴械倒计时）与武器蓄力清零（`resetAttackCooldown`），杜绝因误判导致玩家无法挥刀的恶性体验。
- **独立拦截器范围**：新建 `CombatMitigationHandler`，实行**全面双重拦截**——同时在 Bukkit `EntityDamageByEntityEvent` 与 PacketEvents 网络层 `INTERACT_ENTITY (ATTACK)` 数据包实施即时阻断。
- **玩家交互反馈**：采用**纯静默取消**策略，受击者无伤、作弊者无攻击效果，不发送 ActionBar 提示或音效，仅递增 VL、记录审计日志并向管理员发送告警。

---

## 1. 核心架构与功能概览 (Overview & Core Concept)

- **配置彻底解耦**：
  - `config.yml` 仅保留核心系统设置（多语言、兼容性、统一审计、Setback 回退总控、VL 衰减、管理告警与 GDPR 隐私）。
  - `checks.yml` 独立接管全部 14 个检测项的启用开关、VL 上限与细化检测阈值（包括 Speed, Fly, NoFall, Reach, AutoClicker, KillAura, Velocity, FastBreak, Nuker, Scaffold, InventoryMove, BadPackets, ElytraFly, Jesus）。
- **纯净直接的战斗拦截**：
  - 摆脱原先破坏 Minecraft 战斗手感的侵入式机制（如强制重置 1.9+ 蓄力条、长达数秒的禁止攻击）；
  - 转向工业级反作弊主流的**即时事件阻断 + 网络层数据包拦截**，一旦判定作弊，伤害和击退当帧彻底归零。
- **独立的 `CombatMitigationHandler`**：
  - 作为插件独立的战斗拦截中枢，统一收拢所有战斗类违规的处置逻辑；
  - 建立“网络包拦截标记”与“Bukkit 伤害事件”的 Tick 内关联，杜绝事件流漏网与非线性重入。

---

## 2. 系统交互与反馈设计 (User Experience & Feedback)

- **管理员与控制台视角**：
  - 结构更清晰：修改检测阈值时只需编辑 `checks.yml`，执行 `/argus reload` 即可实时热重载。
  - 告警依然精准：管理员收到的告警格式保持一致，明确记录违规检测项、当前 VL 与网络 Ping。
- **玩家端战斗体感**：
  - **正常玩家**：完全不受任何战斗抑制或武器冷却归零影响，PVP 连招手感原汁原味。
  - **作弊玩家**：开启 KillAura / 超远 Reach 时，刀刀挥出但均被静默取消（无受击判定、无击退、不掉血），无冗余屏幕警告刷屏。

---

## 3. 关键设计决策与权衡 (Key Decisions & Trade-Offs)

| 决策维度 | 选定方案 | 为什么选择此方案 | 备选方案及未采用原因 |
|---|---|---|---|
| **配置文件管理** | 独立单个 `checks.yml` | 集中管理直观、配置热重载高效、易于排查和迁移 | 拆分多子文件：增加维护复杂度；兼容旧 config：产生冗余逻辑 |
| **KillAura 机制精简** | 彻底移除 Combat Throttle 与蓄力清零 | 消除 PVP 体验破坏，符合主流竞技反作弊的静默阻断标准 | 保留开关：存在废弃代码和死分支，容易导致维护混乱 |
| **战斗拦截架构** | 新建 `CombatMitigationHandler` | 职责单一（SRP 原则），实现网络层与事件层的双重状态穿透防御 | 散落在各检测类中：重复写 `event.setCancelled()`，缺少统一防穿透机制 |

---

## 4. 技术架构与数据流图 (Technical Architecture)

```
                 [ 客户端 Client Packet ]
                            │
                            ▼
              ┌───────────────────────────┐
              │   PacketPipelineManager   │
              │  (PacketEvents Netty 层)  │
              └─────────────┬─────────────┘
                            │ (INTERACT_ENTITY)
                            ▼
              ┌───────────────────────────┐
              │  CombatMitigationHandler  │◄──── [ checks.yml ]
              │ (网络层攻击包检查与即时阻断)│      (独立检测配置)
              └─────────────┬─────────────┘
                            │ (通过/阻断记录)
                            ▼
              ┌───────────────────────────┐
              │    Bukkit 伤害事件管线    │
              │(EntityDamageByEntityEvent)│
              └─────────────┬─────────────┘
                            │
                            ▼
              ┌───────────────────────────┐
              │  CombatMitigationHandler  │
              │   (综合判定与静默取消伤害)  │
              └─────────────┬─────────────┘
                            │
               ┌────────────┴────────────┐
               ▼                         ▼
      [ event.setCancelled ]    [ 触发 Alert & 审计日志 ]
      [ 伤害/击退彻底归零 ]     [ 记录 VL / SHA-256 链 ]
```

---

## 5. 详细改造实施步骤 (Implementation Steps)

### 步骤 1：新建独立 `checks.yml` 并瘦身 `config.yml`
1. 在 `src/main/resources/checks.yml` 中完整定义全部 14 个检测项的配置，移除其中的 `combat-throttle` 和 `reset-cooldown` 字段。
2. 从 `src/main/resources/config.yml` 中彻底剔除 `checks:` 节点，使两份配置文件职责划分纯粹。
3. 在 `ArgusACPlugin.java` 中新增 `checksConfig` 资源加载、读取与保存接口：
   - `initChecksConfig()`：若 `checks.yml` 不存在则从 resources 释放；
   - `getChecksConfig()` 与 `reloadChecksConfig()`。
4. 改造 `AbstractCheck` 与 `CheckManager`，所有检测项一律从 `plugin.getChecksConfig()` 读取配置。

### 步骤 2：精简 KillAura 与 PlayerData
1. 在 `KillAuraCheck.java` 中彻底移除：
   - 变量：`combatThrottleEnabled`, `combatThrottleMs`, `combatThrottleMinVl`, `resetCooldown`；
   - 逻辑：删除 `data.isCombatThrottled()` 判定分支与 `applyMitigation()` 中的蓄力清零、战斗抑制设定。
2. 在 `PlayerData.java` 中移除 `combatThrottledUntil`、`isCombatThrottled()` 及相关多余状态。

### 步骤 3：构建 `CombatMitigationHandler` 独立战斗防护类
1. 在 `com.example.argusac.manager`（或相关包）中新建 `CombatMitigationHandler`：
   - 提供 `cancelDamage(EntityDamageByEntityEvent event, Player attacker, Entity victim, String checkName, String reason)`：
     - 当帧 `event.setCancelled(true)`；
     - 安全设置 `event.setDamage(0.0)` 兜底防止其他插件强制反取消；
     - 触发 `ViolationManager.flag(...)`；
   - 提供 `handlePacketAttack(...)`：
     - 解析 PacketEvents 的实体交互包；
     - 记录并阻断可疑包，同步短时阻断上下文；
   - 纯静默运行：不向作弊者发送任何 ActionBar 或 Sound 提示。
2. 重构 `KillAuraCheck`、`ReachCheck` 与 `PlayerEventListener`，将所有的战斗伤害拦截统一托管至 `CombatMitigationHandler`。

### 步骤 4：编译验证与全量自检
1. 使用 `compile_applet` 进行编译验证，确保没有任何编译警告或未引用的旧方法。
2. 校验配置热重载逻辑与各检测项对 `checks.yml` 的正确绑定。
