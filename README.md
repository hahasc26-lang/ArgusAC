# ArgusAC

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.12.2--1.26.3-brightgreen?style=flat-square&logo=minecraft" alt="Minecraft Version">
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Build-Maven-blue?style=flat-square&logo=apachemaven" alt="Maven">
  <img src="https://img.shields.io/badge/Platform-Bukkit%20%7C%20Spigot%20%7C%20Paper-purple?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License">
</p>

<p align="center">
  <a href="https://ko-fi.com/X5Z326V65O"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="ko-fi"></a>
</p>

---

<p align="center">
  <a href="#chinese">中文</a> | <b>English</b>
</p>

<a name="english"></a>

> **ArgusAC** is a high-performance, deterministic anti-cheat stopping movement, combat, and timer cheats with zero false flags.

---

## Performance & Features

- **Extreme Performance & Low Overhead (<0.01ms/player)** — Algorithmic approximations avoiding heavy square roots, zero allocation in tick hot paths, and lock-free async logging maintain a rock-solid 20.0 TPS.
- **Deterministic Physics Simulation** — Evaluates player movement against vanilla kinematic equations, computing theoretical envelopes, residuals, and buffer accumulation to catch mini-speed and micro-hops.
- **Lag-Compensated Entity Hitbox Backtracking** — Stores per-tick AABB hitboxes over a 1,000 ms sliding window, rewinding to the attacker's perspective to enforce the 3.00-block theoretical reach limit without false flags on knockback.
- **Protocol-Level Clock-Drift & Timer Engine** — Enforces a 50 ms per-tick nanosecond balance at the network layer to eliminate 1.05x–2.0x timer speedups, packet floods, and fast item consumption.
- **Zero External Dependencies** — PacketEvents 2.7.0 is shaded and relocated directly into the JAR; plug-and-play out of the box.
- **Triple Setbacks & Tamper-Evident Logs** — Instant `CANCEL` (Recommended) with packet-level sync and locked anchor state, smooth `SOFT` correction, and authoritative `HARD` teleport setbacks, paired with SHA-256 cryptographic audit logs and ASCII motion trace replays.

---

## Installation

### Requirements

- Minecraft server version: **1.12.2–1.26.3 (26.3)** (Spigot / Paper)
- **Java 17** runtime
- **Zero external dependencies** — Self-contained with shaded PacketEvents; plug-and-play!

### Quick Start

1. Download the latest `ArgusAC` JAR from [Releases](../../releases).
2. Place the JAR into your server's `plugins/` directory.
3. Start or restart the server to generate configuration files under `plugins/ArgusAC/`.
4. Run `/argus compat` to verify check compatibility and active adapters.
5. Customize thresholds in `plugins/ArgusAC/config.yml` and hot-reload via `/argus reload`.

---

## Checks

ArgusAC provides comprehensive, non-intrusive cheat coverage across 26 dedicated check modules and sub-checks:

### 1. Movement Checks (12 Checks)

| Check | Focus Area | Description |
|---|---|---|
| `Speed` | Ground & Air | Deterministic kinematic simulation across ground friction (ice, slime, blue ice), water, and air acceleration (bhop) |
| `Fly` | Vertical Motion | Gravitational simulation, abnormal vertical ascent, anti-gravity floating, and hovering |
| `NoFall` | Ground State | Packet-level `onGround` spoofing interception verified against server-side physical collision boxes |
| `InventoryMove` | UI Interaction | Detects moving, sprinting, or jumping while viewing inventories, chests, or container screens |
| `ElytraSpeed` | Gliding Speed & Energy | Kinematic energy conservation enforcing unpowered speed decay and pitch-assisted dive limits (Anti-ElytraFly / Control Mode) |
| `ElytraAscend` | Unpowered Climb | Mechanical energy constraints ($E=mgh+\frac{1}{2}mv^2$) blocking unpowered climb and infinite ascent |
| `ElytraHover` | Mid-Air Stalls & Cruise | Intercepts zero-motion hovering stalls and unpowered flat cruising cheats (PacketFly / Float / Control Mode) |
| `ElytraBoost` | Propulsion Physics | Validates firework rocket and riptide trident boost duration, impulse decay, and propulsion physics |
| `ElytraState` | Slot 38 & Durability Lock | Real-time chestplate slot 38 lock & durability verification, intercepting armor hot-swap packet spoofing |
| `ElytraTimer` | Packet Timing | Enforces client packet timing intervals during high-speed gliding against timer acceleration |
| `ElytraToggle` | State Toggling Rate Limit | Blocks rapid glide toggling (GlideFlip) used to spoof momentum, phase through blocks, or bypass movement limits |
| `ElytraNoFall` | Fall Damage Bypass | Prevents toggling elytra glide at the ground proximity to cancel accumulated fall damage |

### 2. Combat Checks (5 Checks)

| Check | Focus Area | Description |
|---|---|---|
| `Reach` | Attack Distance | Lag-compensated entity bounding box backtracking strictly enforcing the 3.00-block theoretical limit |
| `AutoClicker` | Click Rate & Patterns | Netty-layer packet sampling checking maximum CPS and statistical interval variance (macro/autoclicker detection) |
| `KillAura` | Target & Angles | Ray-traced obstacle occlusion, impossible viewing angle deviation, silent aim misses, and rapid multi-target switching |
| `Velocity` | Knockback Response | Validates horizontal and vertical knockback response ratios and tracks pending server velocity packets (Anti-KB) |
| `FastBow` | Bow Charge & Burst | Server-side charge tick counter (20-tick baseline with 2-tick tolerance), sliding-window burst protection, shot rate limiting, and dual CANCEL / NERF mitigations |

### 3. World Interaction Checks (3 Checks)

| Check | Focus Area | Description |
|---|---|---|
| `FastBreak` | Block Breaking | Calculates theoretical block hardness and tool enchantment digging time to prevent speed breaking |
| `Nuker` | Mass Block Breaking | Restricts blocks broken per tick and per second to vanilla limits without Haste |
| `Scaffold` | Automatic Bridging | Analyzes downward pitch angles, sprint bridging without sneaking, and rapid block placement (FastPlace) |

### 4. Packet & Protocol Checks (6 Checks)

| Check | Focus Area | Description |
|---|---|---|
| `BadPackets (Pitch)` | Protocol Limits | Drops and flags impossible pitch rotations outside [-90.0, 90.0] (Derp / HeadRoll cheats) |
| `BadPackets (Coordinates)` | Floating Limits | Intercepts NaN, Infinite, and world-border-exceeding raw packet coordinates |
| `BadPackets (Timer)` | Clock Drift | 50 ms/tick nanosecond balance algorithm catching 1.05x–2.0x game speedups and packet bursts |
| `BadPackets (GroundSpoof)` | Protocol Integrity | Intercepts airborne packets falsely reporting `onGround=true` before physics processing |
| `BadPackets (Actions)` | State Sequence | Detects conflicting entity actions (e.g., instant sneak-sprint exploits) and moving while asleep |
| `BadPackets (Duplicate)` | Packet Redundancy | Filters duplicate position packets sent within the same tick |

Check parameters can be tuned in [`checks.yml`](src/main/resources/checks.yml). Use `/argus compat` to verify compatibility across versions.

### FastBow Defense & Mitigation Modes

ArgusAC features dedicated server-side physics and protocol-level defenses against rapid bow/crossbow exploits (Gatling Bow, Machine Gun Bow, Instant Bow, Packet Spoil / Blink bursts):

- **Server-Side Charge Tick Counter** — Pulling a bow to full force ($1.0$) strictly requires 20 vanilla ticks (1,000 ms). ArgusAC timestamps bow draw interactions (`USE_ITEM` packet & Bukkit interact) and verifies elapsed ticks upon arrow release (`EntityShootBowEvent`), allowing an adaptive 1–2 tick jitter tolerance ($\ge 18$ ticks / 850 ms). Also supports Crossbow Quick Charge enchantments.
- **Transaction & Sliding-Window Burst Defense** — Intercepts FakeLag and Blink exploits where clients freeze packet dispatch and unleash a burst of arrows within a single tick; limits shots to physically feasible rates within a 1,000 ms sliding window.
- **Shot Rate Limiting** — Enforces a physical cooldown window between consecutive shots ($\ge 250$ ms) to prevent macro-assisted rapid firing.
- **Dual Mitigation Strategies**:
  - **`CANCEL` (Default / Recommended)**: Silently drops the shoot event and removes the arrow entity on the server tick, preventing arrow rain from overloading the server without consuming ammunition.
  - **`NERF` (Limp Noodle Arrow)**: Neutralizes the projectile's initial velocity down to zero ($v_y = -0.05$), making the arrow drop harmlessly at the shooter's feet.

---

## Setback System

ArgusAC caches a player's last validated location after passing check evaluations:

- **`CANCEL` (Recommended)** — Directly cancels the illegal move event in real-time within the tick for instant, zero-latency mitigation without rubberbanding.
- **`HARD`** — Teleports the player to the validated location on the main thread, resetting velocity and fall distance.
- **`SOFT`** — Corrects the movement destination within the move event for a smooth, rubberband-free correction.

Setbacks include an adaptive latency-aware immunity window to prevent cascading false flags. If the primary setback point is obstructed, a secondary fallback location is selected automatically.

---

## Commands

<details>
<summary><b>Click to expand / collapse command list</b></summary>

<br>

Main command: **`/argus`** (aliases: **`/ac`**, **`/argusac`**).

| Command | Description | Permission |
|---|---|---|
| `/argus help` | Show the command list | Everyone |
| `/argus reload` | Reload configuration, checks, and language files | `argusac.admin` |
| `/argus vl <player>` | View an online player's current violation levels | `argusac.admin` |
| `/argus logs <player> [options]` | Query recent memory logs (`--check`, `--since`, `--setback`, `--page`) | `argusac.admin` |
| `/argus history <player> <days> [options]` | Search historical compressed audit archives | `argusac.admin` |
| `/argus replay <player> [--side]` | Render an ASCII lightweight movement trace | `argusac.admin` |
| `/argus export <player>` | Export a player's complete audit records as a ZIP | `argusac.admin` |
| `/argus top [--since <1h\|24h\|7d>]` | Show the top 10 highest violation counts | `argusac.admin` |
| `/argus status` | Show audit queue capacity and worker status | `argusac.admin` |
| `/argus verify` | Verify SHA-256 cryptographic log chain integrity | `argusac.admin` |
| `/argus debug <check>` | Toggle live debug logging for a check | `argusac.admin` |
| `/argus compat` | Display multi-version capability matrix and active tiers | `argusac.admin` |
| `/argus toggle <check>` | Toggle a check module on/off at runtime | `argusac.admin` |
| `/argus setback <player\|global> <disable\|cancel\|soft\|hard\|default\|now\|info>` | Inspect or configure setback engine modes | `argusac.admin` |
| `/argus alerts [on\|off\|status]` | Mute or restore personal staff alerts for this login session | `argusac.alert` |
| `/argus my-data` | View GDPR data retention and anonymization policies | Everyone |
| `/argus purge <player>` | Safely delete a player's stored logs and generate an audit record | `argusac.admin` |

</details>

---

## Permissions

<details>
<summary><b>Click to expand / collapse permissions</b></summary>

<br>

| Permission | Default | Description |
|---|---|---|
| `argusac.admin` | OP | Access all `/argus` administration commands |
| `argusac.alert` | OP | Receive real-time violation and setback alerts |
| `argusac.bypass` | Nobody | Bypass all checks and setback enforcement |

</details>

---

## Configuration and Audit Logs

Configuration resides at `plugins/ArgusAC/config.yml`. It includes i18n settings, version policies, physics prediction tolerances, violation thresholds, alerts, setbacks, and GDPR privacy settings.

Audit logs are stored asynchronously as JSON Lines under `plugins/ArgusAC/logs/setbacks.jsonl`. Supports automated rotation, compression, IP anonymization, and SHA-256 tamper-evident verification.

---

## License

MIT License.

---

<a name="chinese"></a>

<p align="center">
  <b>中文</b> | <a href="#english">English</a>
</p>

> **ArgusAC** 是一款确定性物理驱动的高性能反作弊，精准拦截移动、战斗与变速作弊，零误判、零卡顿。

---

## 性能与核心特性

- **极致性能与近零开销 (<0.01ms/人)** — 全流程算法优化，严格规避高开销开方运算，热点路径零对象分配，无锁环形异步日志，确保 20.0 TPS 稳定如初。
- **确定性物理模拟预测 (Deterministic Kinematics)** — 拟合原版运动学微分方程（摩擦力、重力、阻力），以残差（Residual）与缓冲（Buffer）精准捕获微速（Mini-Speed）与慢速微跳。
- **实体历史碰撞盒延迟补偿回溯 (Lag Backtracking)** — 维护 1000ms 碰撞盒快照，攻击时根据 Ping 回溯历史视野，消灭击退误报并将 Reach 严格锁定在 3.00 格物理极限。
- **协议级 Timer 时钟平衡引擎 (Clock Drift Engine)** — 50ms/tick 纳秒时钟银行追踪发包时钟漂移，秒级拦截 1.05x ~ 2.0x 变速齿轮、快速进食与发包轰炸。
- **开箱即用，零外部依赖** — PacketEvents 2.7.0 原生嵌入并包重定向（Shaded Relocation），无需额外安装任何前置插件。
- **三模回退与防篡改审计** — `CANCEL`（推荐，当帧双重即时阻断、底层发包同步与原位强行锚定）、`HARD`（瞬移）与 `SOFT`（平滑修正），辅以 SHA-256 密码学防篡改日志与 ASCII 轨迹回放。

---

## 安装

### 环境要求

- Minecraft 服务端版本：**1.12.2–1.26.3 (26.3)** (Spigot / Paper)
- **Java 17** 运行环境
- **零外部前置依赖** — 网络协议引擎 PacketEvents 已内置打包，开箱即用！

### 快速开始

1. 从 [Releases](../../releases) 下载最新的 `ArgusAC` JAR。
2. 将 JAR 放入服务端的 `plugins/` 目录。
3. 启动或重启服务端，插件会在 `plugins/ArgusAC/` 下生成默认配置。
4. 执行 `/argus compat` 检查当前环境检测项兼容性与适配器状态。
5. 在 `plugins/ArgusAC/config.yml` 中调整参数，使用 `/argus reload` 热重载生效。

---

## 检测模块 (Checks)

ArgusAC 构建了四层纵深防御体系，共计 26 个独立检测模块与细分子项，全方位覆盖各类作弊行为：

### 1. 移动类检测 (Movement Checks - 12项)

| 检测项 | 核心领域 | 说明 |
|---|---|---|
| `Speed` | 地面与空中速度 | 确定性运动学微分方程拟合，覆盖冰面摩擦、粘液块、药水加速与空中加速度（BHop） |
| `Fly` | 垂直移动与重力模拟 | 确定性重力加速度预测，检测反重力上升、慢速微跳（Mini-Jump）与空中悬浮（Hover） |
| `NoFall` | 接地状态欺诈 | 数据包层虚假接地标志（`onGround`）实时拦截与服务端物理碰撞体着陆校验 |
| `InventoryMove` | 界面移动交互 | 检测在打开物品栏、箱子或各种容器界面时发生的非法移动、疾跑与起跳 |
| `ElytraSpeed` | 鞘翅速度与动能衰减 | 运动学机械能守恒，限制平飞与俯冲动能换算，拦截“甲飞”超速机动与 Control Mode |
| `ElytraAscend` | 无动力仰角爬升 | 机械能守恒约束（$E=mgh+\frac{1}{2}mv^2$），拦截无烟花/激流推进时的反常向上仰角飞升与“甲飞”无限爬升 |
| `ElytraHover` | 悬停滞空与无限巡航 | 识别空中零动量悬空挂机（PacketFly/悬浮）及无动力定高无限平飞巡航等“甲飞”外挂 |
| `ElytraBoost` | 冲量与推进物理 | 校验烟花火箭与三叉戟激流加速的推进时限与冲量衰减物理学 |
| `ElytraState` | 38号槽位与换甲锁定 | 实时强校验胸甲槽位与耐久（耐久$\le 1$不可飞），当帧阻断“换甲-开伞-换回胸甲”偷飞与伪造滑翔姿态 |
| `ElytraTimer` | 飞行数据包时序 | 限制高速滑翔过程中的数据包发包频率与时钟步进，拦截 Timer 变速发包加速 |
| `ElytraToggle` | 高频姿态切换限频 | 限制单秒内开关滑翔次数，拦截高频反复开关鞘翅滑翔以刷初速、穿墙或卡滞空的漏洞（GlideFlip） |
| `ElytraNoFall` | 贴地滑翔免摔伤 | 防止在落地最后一瞬间开启滑翔姿态规避高空坠落摔伤 |

### 2. 战斗类检测 (Combat Checks - 5项)

| 检测项 | 核心领域 | 说明 |
|---|---|---|
| `Reach` | 攻击距离 | 实体历史碰撞盒延迟补偿回溯，极限收敛在 3.00 格原版理论限制，击退不误判 |
| `AutoClicker` | 连点频率与规律 | 网络底层纳秒采样，限制极限 CPS 并通过样本标准差识别极其机械的无抖动连点器 |
| `KillAura` | 视角与隔墙打击 | 回溯坐标计算视角夹角、跨版本安全视线遮挡判定、脱靶命中与极速多目标切换 |
| `Velocity` | 击退动量响应 | 校验垂直与水平受击退响应比率，追踪服务端下发的待确认击退时序（Anti-KB） |
| `FastBow` | 弓箭蓄力与射速限制 | 服务端 Tick 级蓄力计时器（20 ticks 基准）、1000ms 滑动窗口防 FakeLag 堆包轰炸与全局射击冷却，专设 CANCEL（静默取消）与 NERF（软绵箭）双模式 |

### 3. 世界交互类检测 (World Interaction Checks - 3项)

| 检测项 | 核心领域 | 说明 |
|---|---|---|
| `FastBreak` | 异常方块破坏 | 比对方块硬度、工具挖掘效率与附魔理论时限，阻断加速挖掘 |
| `Nuker` | 大范围拆除 | 限制单个 Tick 与每秒内破坏方块的上限数量（防暴拆/全自动挖矿） |
| `Scaffold` | 自动搭桥铺路 | 检测异常低头俯仰角、疾跑倒退搭桥与极速放置方块（FastPlace） |

### 4. 数据包与协议类检测 (Packet & Protocol Checks - 6项)

| 检测项 | 核心领域 | 说明 |
|---|---|---|
| `BadPackets (Pitch)` | 协议视角越界 | 拦截丢弃超越正负 90 度的非法俯仰角（Derp / 倒立视角外挂） |
| `BadPackets (Coordinates)` | 坐标浮点异常 | 实时过滤拦截 NaN、Infinite 与超出世界边界的恶意异常坐标包 |
| `BadPackets (Timer)` | 时钟漂移变速 | 50ms/tick 纳秒时钟银行算法，捕捉 1.05x ~ 2.0x 变速齿轮与发包轰炸 |
| `BadPackets (GroundSpoof)` | 虚假接地欺骗 | 在网络层直接捕获空中伪造 `onGround=true` 的数据包 |
| `BadPackets (Actions)` | 状态时序冲突 | 拦截潜行疾跑瞬时矛盾动作包与睡眠状态异常移动包 |
| `BadPackets (Duplicate)` | 数据包冗余 | 过滤客户端同 tick 内发送的重复冗余位置包 |

检测阈值与参数均可在 [`checks.yml`](src/main/resources/checks.yml) 中细致调节。使用 `/argus compat` 查看跨版本能力分配。

### 快速射箭 (FastBow) 深度防御与处置模式

ArgusAC 针对 FastBow / 加特林弓外挂（瞬发箭、绕过蓄力、Blink 延迟堆包轰炸）构建了确定性服务端物理校验：

- **服务端 Tick 级蓄力计时器** — 原版弓箭拉满蓄力严格需要 20 个 Tick（1000ms）。ArgusAC 在举弓打点（协议包 `USE_ITEM` 与 Bukkit 交互）记录初始 Tick 与时间戳，在释放射箭（`EntityShootBowEvent`）时核算蓄力时效，放宽 1~2 Ticks（$\ge 18$ Ticks / 850ms）网络抖动容限；同时原生适配 1.14+ 弩（Crossbow）快速装填（Quick Charge）附魔。
- **事务与滑动窗口防延迟堆包** — 针对挂端通过 FakeLag / Blink 制造网络阻塞假象、恢复发包瞬间倾泻十余支箭矢的包轰炸（Packet Spoil），在 1000ms 滑动窗口内设置物理释放上限（默认 $\le 3$ 支/秒）。
- **全局射击冷却与频率限制** — 强制两箭之间必须具备基础动作切换耗时（默认 $\ge 250$ms 绝对物理间歇），拦截宏与极速连发。
- **专设双重防御处置策略**：
  - **`CANCEL`（默认推荐）**：当帧静默取消射箭事件并清除箭矢实体，不消耗玩家背包箭矢与弓耐久，杜绝客户端箭雨卡顿服务端。
  - **`NERF`（软绵箭模式）**：强行将箭矢初速度削减归零（$v_y = -0.05$），使箭矢如无害软绵般垂直掉落在射手脚下。

---

## 状态回退与取消系统 (Setback System)

ArgusAC 在玩家通过移动验证后记录其最后合法位置：

- **`CANCEL` (推荐)** — 当帧零延迟直接在事件内取消违规移动，实现类似 GrimAC 的瞬时精准阻断，无拉扯感。
- **`HARD`** — 主线程传送回已记录位置，清空动量速度与坠落距离。
- **`SOFT`** — 在移动事件中修正目标坐标，提供无拉扯感平滑修正。

回退引擎内置自适应网络延迟的临时免疫期，避免产生连续误报；若首要位置受阻，自动选取备用合法点。

---

## 命令

<details>
<summary><b>点击展开 / 折叠命令列表</b></summary>

<br>

主命令为 **`/argus`**（别名：**`/ac`**、**`/argusac`**）。

| 命令 | 说明 | 权限 |
|---|---|---|
| `/argus help` | 显示命令列表 | 所有人 |
| `/argus reload` | 重载配置、检测模块与多语言文件 | `argusac.admin` |
| `/argus vl <玩家>` | 查看在线玩家的各模块违规等级 (VL) | `argusac.admin` |
| `/argus logs <玩家> [选项]` | 查询近期内存日志（支持 `--check`、`--since`、`--setback`、`--page`） | `argusac.admin` |
| `/argus history <玩家> <天数> [选项]` | 异步流式检索历史审计归档文件 | `argusac.admin` |
| `/argus replay <玩家> [--side]` | 输出 ASCII 轻量二维/三维移动轨迹 | `argusac.admin` |
| `/argus export <玩家>` | 将玩家审计数据完整打包导出为 ZIP | `argusac.admin` |
| `/argus top [--since <1h\|24h\|7d>]` | 显示违规次数前十名排行榜 | `argusac.admin` |
| `/argus status` | 查看审计队列吞吐指标与运行状态 | `argusac.admin` |
| `/argus verify` | 校验全局日志 SHA-256 哈希链完整性 | `argusac.admin` |
| `/argus debug <检测项>` | 实时开关指定检测项的调试控制台日志 | `argusac.admin` |
| `/argus compat` | 查看跨版本能力矩阵与降级自检报告 | `argusac.admin` |
| `/argus toggle <检测项>` | 运行时热开关指定检测模块 | `argusac.admin` |
| `/argus setback <玩家\|global> <disable\|cancel\|soft\|hard\|default\|now\|info>` | 管理或诊断状态回退子系统模式 | `argusac.admin` |
| `/argus alerts [on\|off\|status]` | 本次登录会话期间关闭、恢复或查看个人管理员告警提示 | `argusac.alert` |
| `/argus my-data` | 查看 GDPR 个人数据保留策略与匿名化透明度 | 所有人 |
| `/argus purge <玩家>` | 彻底删除玩家存储记录并生成系统注销审计条目 | `argusac.admin` |

</details>

---

## 权限

<details>
<summary><b>点击展开 / 折叠权限节点</b></summary>

<br>

| 权限 | 默认 | 说明 |
|---|---|---|
| `argusac.admin` | OP | 拥有所有 `/argus` 管理命令的执行权限 |
| `argusac.alert` | OP | 接收违规与回退实时告警提示 |
| `argusac.bypass` | 无人 | 豁免全部作弊检测与状态回退处置 |

</details>

---

## 配置与审计日志

默认配置文件生成在 `plugins/ArgusAC/config.yml`，包含多语言选择、版本降级策略、物理预测容差、检测参数、告警、回退策略与 GDPR 隐私选项。

审计日志默认以高性能 JSON Lines 格式异步写入 `plugins/ArgusAC/logs/setbacks.jsonl`，原生支持文件自动轮转、GZIP 压缩归档、IP 哈希加密与 SHA-256 哈希链防伪防篡改。

---

## 许可证

MIT 许可证。
