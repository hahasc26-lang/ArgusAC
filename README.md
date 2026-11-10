# ArgusAC

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.12.2--26.3-brightgreen?style=flat-square&logo=minecraft" alt="Minecraft Version">
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

> **ArgusAC** is a high-performance anti-cheat for Paper and Spigot servers, designed to accurately stop movement, combat, and timer cheats while keeping server tick rates stable.

---

## Highlights

- **High Efficiency** — Highly optimized movement tracking and asynchronous logging ensure smooth server performance without dipping TPS.
- **Physics-Based Movement Validation** — Validates player motions against vanilla movement physics (friction, gravity, and fluid dynamics) to stop speeds, micro-hops, and fly hacks.
- **Lag-Compensated Combat Checks** — Tracks recent entity hitboxes to evaluate hit ranges from the attacker's perspective, avoiding reach false flags caused by network latency or knockback.
- **Timer & Fast Item Defense** — Accurately detects speedhacks (Timer), fast bow firing, and instant eating by tracking client packet arrival rates and server-side action ticks.
- **Self-Contained** — PacketEvents is shaded into the jar; ready to run without external dependencies.
- **Flexible Mitigation & Audit Logs** — Supports instant movement cancellation (`CANCEL`), smooth correction (`SOFT`), and traditional teleport setbacks (`HARD`), along with detailed violation history and replay traces.

---

## Installation

### Requirements

- Server version: **1.12.2–26.3** (Spigot / Paper)
- Runtime: **Java 17+**
- Dependencies: **None** (Plug and play)

### Quick Start

1. Download the latest `ArgusAC` JAR from [Releases](../../releases).
2. Place the JAR into your server's `plugins/` directory.
3. Start or restart the server to generate configuration files in `plugins/ArgusAC/`.
4. Use `/argus reload` to apply configuration changes at any time.

---

## Checks

### 1. Movement Checks

| Check | Focus Area | Description |
|---|---|---|
| `Speed` | Speed Hacks | Detects abnormal ground and airborne horizontal velocity, factoring in friction, potions, and ice |
| `Fly` | Flight & Hover | Detects unauthorized vertical ascent, gravity avoidance, and mid-air hovering |
| `NoFall` | Fall Damage Bypass | Catches packet-level spoofed ground states to prevent skipping fall damage |
| `InventoryMove` | Container Movement | Prevents walking, sprinting, or jumping while an inventory or chest is open |
| `ElytraSpeed` | Gliding Speed | Enforces realistic glide speeds, accounting for dive gravity and rocket boosts |
| `ElytraAscend` | Infinite Ascent | Blocks unpowered vertical climbs and unnatural continuous upward gliding |
| `ElytraHover` | Air Stall | Detects freezing in mid-air or cruising horizontally without propulsion |
| `ElytraBoost` | Propulsion | Verifies firework rocket and riptide trident boost durations |
| `ElytraState` | Chestplate Swapping | Verifies equipped elytra status and durability during flight to prevent hot-swap exploits |
| `ElytraTimer` | Packet Acceleration | Detects timer speedups during high-speed gliding |
| `ElytraToggle` | Quick Toggling | Prevents rapidly toggling glide state to gain momentum or bypass physics |
| `ElytraNoFall` | Gliding Landing Exploit | Prevents tapping elytra flight right before landing to bypass fall damage |

### 2. Combat Checks

| Check | Focus Area | Description |
|---|---|---|
| `Reach` | Attack Distance | Accurately enforces the 3.0-block vanilla reach limit with latency compensation |
| `AutoClicker` | Click Speed & Patterns | Detects excessive CPS rates and inhuman click interval patterns |
| `KillAura` | Aim & Targets | Detects impossible angles, attacking through obstacles, and abnormal multi-target snapping |
| `Velocity` | Knockback | Verifies that players take appropriate knockback from hits and explosions |
| `FastBow` | Bow Shooting | Enforces realistic draw time requirements and limits rapid-fire shooting |
| `FastEat` | Item Consumption | Enforces vanilla item consumption times to prevent instant eating |
| `Mace` | Heavy Mace | Validates mace smash attacks, fall heights, and damage limits in 1.21+ |

### 3. World Interaction Checks

| Check | Focus Area | Description |
|---|---|---|
| `FastBreak` | Block Breaking | Calculates valid dig durations based on tool, enchantments, and block hardness |
| `Nuker` | Mass Destruction | Restricts the number of blocks broken per tick to vanilla possibilities |
| `Scaffold` | Auto-Bridging | Detects fast bridging with unnatural downward angles and sprint placement |

### 4. Packet & Protocol Checks

| Check | Focus Area | Description |
|---|---|---|
| `BadPackets (Pitch)` | Head Pitch | Drops impossible vertical viewing angles exceeding [-90, 90] |
| `BadPackets (Coordinates)` | Coordinate Bounds | Filters out invalid coordinates (NaN, Infinity, or out of world limits) |
| `BadPackets (Timer)` | Game Speedup | Detects client clock acceleration (1.05x–2.0x game speedups) |
| `BadPackets (GroundSpoof)` | Ground Status | Catches packets falsely reporting on-ground status while suspended |
| `BadPackets (Actions)` | State Consistency | Blocks conflicting movement states (such as sneaking while sprinting) |
| `BadPackets (Duplicate)` | Duplicate Packets | Filters redundant position packets sent within the same tick |

---

## Setback Modes

When irregular movement is detected, ArgusAC can handle it in three ways:

- **`CANCEL` (Default & Recommended)** — Immediately cancels the illegal move packet on the current tick. Zero latency, no camera snapping, and no rubberbanding.
- **`HARD`** — Teleports the player back to their last safe location and resets falling momentum.
- **`SOFT`** — Adjusts the player's movement target coordinate smoothly within the move event.

---

## Commands & Permissions

Main command: **`/argus`** (aliases: **`/ac`**, **`/argusac`**).

| Command | Description | Permission |
|---|---|---|
| `/argus help` | Displays available commands | None |
| `/argus reload` | Reloads configuration, checks, and language files | `argusac.admin` |
| `/argus vl <player>` | Views violation levels of a player | `argusac.admin` |
| `/argus logs <player>` | Queries recent in-memory violation records | `argusac.admin` |
| `/argus history <player> <days>` | Searches through historical audit archives | `argusac.admin` |
| `/argus replay <player>` | Generates an ASCII movement trace replay | `argusac.admin` |
| `/argus export <player>` | Exports complete audit records for a player | `argusac.admin` |
| `/argus top` | Displays players with the highest violation counts | `argusac.admin` |
| `/argus setback <player\|global> <mode>` | Inspects or overrides the setback mode | `argusac.admin` |
| `/argus alerts [on\|off]` | Toggles violation alert notifications for staff | `argusac.alert` |
| `/argus update [check\|download]` | Checks or downloads updates from Modrinth | `argusac.admin` |

- `argusac.admin` — Access to all `/argus` management commands (Default: OP)
- `argusac.alert` — Receive cheat detection notifications (Default: OP)
- `argusac.bypass` — Exempt from all anti-cheat checks and mitigations (Default: None)

---

## Configuration & Logs

- **Configuration**: Located at `plugins/ArgusAC/config.yml`. General settings, violation thresholds, language settings, and alerts can be adjusted here. Check-specific rules are located in `checks.yml`.
- **Audit Logs**: Saved to `plugins/ArgusAC/logs/setbacks.jsonl`. Supports automatic log compression, player data export, and GDPR deletion via `/argus purge`.

---

## License

[MIT License](LICENSE).

---

<a name="chinese"></a>

<p align="center">
  <b>中文</b> | <a href="#english">English</a>
</p>

> **ArgusAC** 是一款面向 Paper 与 Spigot 服务端的高性能反作弊插件，专注于精准拦截移动、战斗与变速类作弊，在保障检测准确率的同时最大化节省服务器资源。

---

## 核心特性

- **轻量低开销** — 针对移动与发包校验进行了深度性能优化，配合异步日志审计，保障服务器在高负载下稳定维持 20.0 TPS。
- **物理运动学校验** — 结合原版物理特性（重力加速度、方块摩擦力、水流与跳跃机制）校验移动轨迹，精准拦截微速加速、空中漂移与非正常起跳。
- **延迟补偿与回溯** — 记录实体近期碰撞盒，在攻击发生时根据玩家网络延迟还原当时的相对站位，有效防止误判并严格限制攻击距离。
- **变速齿轮与快速使用防御** — 通过发包频率监控与服务端动作计时，准确拦截变速齿轮（Timer）、快速拉弓与瞬间进食。
- **开箱即用，免前置** — 内部已集成 PacketEvents 数据包引擎，无需额外安装前置插件即可跨版本运行。
- **多种处置模式与轨迹回放** — 支持即时拦截（`CANCEL`，不回弹）、平滑修正（`SOFT`）与传统传送拉回（`HARD`），并提供控制台 ASCII 轨迹回放便于管理员复核。

---

## 安装说明

### 环境要求

- 服务端版本：**1.12.2–26.3** (Spigot / Paper)
- Java 版本：**Java 17+**
- 前置要求：**无任何前置依赖**（开箱即用）

### 快速开始

1. 从 [Releases](../../releases) 页面下载最新的 `ArgusAC.jar`。
2. 将 jar 放入服务端的 `plugins/` 目录中。
3. 启动或重启服务端，插件会自动在 `plugins/ArgusAC/` 下生成配置文件。
4. 修改配置后可随时使用 `/argus reload` 重新加载。

---

## 检测模块

### 1. 移动类检测

| 检测项 | 检测方向 | 功能说明 |
|---|---|---|
| `Speed` | 移动速度 | 综合考虑方块摩擦力、药水效果与跳跃状态，拦截地面与空中超速（BHop） |
| `Fly` | 垂直移动 | 校验垂直加速度与下落行为，拦截反重力上升、异常微跳与空中悬浮 |
| `NoFall` | 虚假落地 | 拦截客户端伪造的 `onGround` 接地数据包，防止利用漏洞免疫摔伤 |
| `InventoryMove` | 界面移动 | 检测在打开背包、箱子或各种容器界面时的非法移动、疾跑与起跳 |
| `ElytraSpeed` | 鞘翅速度 | 校验俯冲与平飞时的滑翔速度，拦截无动力超速巡航与异常机动 |
| `ElytraAscend` | 异常爬升 | 拦截在缺少烟花或激流推进时的异常垂直向上爬升 |
| `ElytraHover` | 空中滞空 | 拦截在空中静止悬停或无动力水平长距离巡航 |
| `ElytraBoost` | 推进持续度 | 校验烟花火箭与三叉戟激流的推进时长与冲量衰减 |
| `ElytraState` | 换甲飞天 | 实时校验鞘翅装备与耐久状态，拦截通过快速切甲伪造滑翔姿态的行为 |
| `ElytraTimer` | 滑翔变速 | 限制滑翔过程中的发包频率，防止利用 Timer 加速飞行 |
| `ElytraToggle` | 姿态快切 | 限制单秒内开关滑翔的频率，防止利用反复开关刷速度或卡模型 |
| `ElytraNoFall` | 落地免伤 | 拦截在接触地面瞬间开启滑翔以规避坠落伤害的行为 |

### 2. 战斗类检测

| 检测项 | 检测方向 | 功能说明 |
|---|---|---|
| `Reach` | 攻击距离 | 结合延迟补偿严格限制攻击判定范围，防止远距离隔空打击 |
| `AutoClicker` | 连点频率 | 监控左键攻击频率与点击间隔规律，识别异常高频连点与机械宏 |
| `KillAura` | 攻击视角 | 检测不可见角度攻击、隔墙打击以及极速多目标切换 |
| `Velocity` | 击退响应 | 校验玩家受击后的实际击退响应比例，拦截防击退（Anti-KB）外挂 |
| `FastBow` | 弓箭射速 | 校验拉弓蓄力时长与发射频率，拦截加特林连发与瞬间射箭 |
| `FastEat` | 进食时间 | 校验使用食物/药水等消耗品的时长，拦截瞬间进食外挂 |
| `Mace` | 重锤重击 | 校验 1.21+ 重锤的下落高度、攻击间隔与伤害上限，防止平地虚假重击 |

### 3. 世界交互类检测

| 检测项 | 检测方向 | 功能说明 |
|---|---|---|
| `FastBreak` | 异常破坏 | 根据工具类型、附魔与方块硬度计算合法破坏时长，拦截加速挖掘 |
| `Nuker` | 大范围拆除 | 限制单 tick 和每秒内破坏方块的上限数量，防止全自动暴拆与矿透快挖 |
| `Scaffold` | 自动搭桥 | 检测异常俯仰角度、倒退疾跑搭桥与极速放置方块的行为 |

### 4. 协议与数据包检测

| 检测项 | 检测方向 | 功能说明 |
|---|---|---|
| `BadPackets (Pitch)` | 视角限制 | 拦截并丢弃超出 [-90, 90] 范围的非法仰角数据包 |
| `BadPackets (Coordinates)` | 异常坐标 | 拦截包含 NaN、无穷大或超出世界边界的异常坐标数据包 |
| `BadPackets (Timer)` | 游戏变速 | 监测客户端发包时钟漂移，拦截 1.05x ~ 2.0x 游戏加速 |
| `BadPackets (GroundSpoof)` | 虚假接地 | 在网络层直接拦截身处空中却报告在地面的虚假数据包 |
| `BadPackets (Actions)` | 动作冲突 | 拦截潜行疾跑同时存在等互相矛盾的状态动作 |
| `BadPackets (Duplicate)` | 重复发包 | 过滤同 tick 内发送的冗余重复位置数据包 |

---

## 违规处置方式 (Setback)

当玩家触发移动违规时，ArgusAC 提供三种可选处置策略：

- **`CANCEL` (推荐，默认)** — 当帧在移动事件内直接取消违规位移。零延迟阻断违规移动，玩家画面平稳不抽搐，彻底消除频繁拉回感。
- **`HARD`** — 强制将玩家传送回最近一次经过验证的合法安全位置，并重置动量与下落距离。
- **`SOFT`** — 在移动事件内平滑修正目标坐标至合法位置，适合需要温和校正的服务器。

---

## 管理命令与权限

主命令为 **`/argus`**（别名：**`/ac`**、**`/argusac`**）。

| 命令 | 说明 | 权限要求 |
|---|---|---|
| `/argus help` | 查看命令帮助菜单 | 无 |
| `/argus reload` | 重新加载配置文件、检测规则与语言文件 | `argusac.admin` |
| `/argus vl <玩家>` | 查看目标玩家当前的违规计分（VL） | `argusac.admin` |
| `/argus logs <玩家>` | 查看目标玩家近期的违规记录 | `argusac.admin` |
| `/argus history <玩家> <天数>` | 查询指定天数内的历史审计归档 | `argusac.admin` |
| `/argus replay <玩家>` | 在控制台生成轻量 ASCII 移动轨迹回放 | `argusac.admin` |
| `/argus export <玩家>` | 将指定玩家的审计数据导出为压缩包 | `argusac.admin` |
| `/argus top` | 查看全服违规点数前十名玩家列表 | `argusac.admin` |
| `/argus setback <玩家\|global> <模式>` | 查看或修改当前违规处置模式 | `argusac.admin` |
| `/argus alerts [on\|off]` | 开关当前登录会话的管理报警提示 | `argusac.alert` |
| `/argus update [check\|download]` | 检查或下载 Modrinth 发布的最新版本 | `argusac.admin` |

- `argusac.admin` — 拥有所有 `/argus` 管理命令的执行权限（默认：OP）
- `argusac.alert` — 接收违规告警与拦截通知（默认：OP）
- `argusac.bypass` — 绕过所有检测与处置（默认：无）

---

## 配置文件与日志

- **配置文件**：主要设置在 `plugins/ArgusAC/config.yml`（包含全局开关、语言选择、告警设置等），详细检测参数在 `checks.yml` 中配置。
- **审计日志**：违规记录默认异步存储在 `plugins/ArgusAC/logs/setbacks.jsonl` 中，支持自动压缩、历史检索与通过 `/argus purge` 进行数据安全清理。

---

## 开源协议

[MIT License](LICENSE)。
