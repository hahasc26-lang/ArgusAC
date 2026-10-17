# ArgusAC

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.12.2--1.21.x-brightgreen?style=flat-square&logo=minecraft" alt="Minecraft Version">
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk" alt="Java 17">
  <img src="https://img.shields.io/badge/Build-Maven-blue?style=flat-square&logo=apachemaven" alt="Maven">
  <img src="https://img.shields.io/badge/Platform-Bukkit%20%7C%20Spigot%20%7C%20Paper-purple?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License">
</p>

---

<p align="center">
  <a href="#chinese">中文</a> | <b>English</b>
</p>

<a name="english"></a>

> A modular anti-cheat plugin for Minecraft servers, with configurable checks, last-known-valid-location setbacks, and an auditable violation history.

---

## Features

- **Modular checks** — Movement, combat, world interaction, and packet checks can be configured and toggled independently.
- **ElytraFly suite** — Eight configurable sub-checks cover speed, ascent, hovering, firework boosts, glide state, timer behavior, rapid toggling, and fall-damage abuse.
- **Last-known-valid-location setbacks** — Reverses invalid movement to a location recorded after successful checks, with `HARD` and `SOFT` modes, per-check overrides, and a post-setback immunity window.
- **Cross-version compatibility layer** — Targets Spigot/Paper 1.12.2–1.21.x and reports degraded or unavailable check capabilities; coverage depends on server version and available APIs.
- **Configurable mitigation and alerts** — Tune violation thresholds, setback behavior, world exemptions, staff notifications, and punishment commands.
- **Audit and log tools** — Query recent and archived logs, export player audit data, visualize movement traces, and verify the SHA-256 hash chain.
- **Internationalization** — Bundled `zh_cn` and `en_us` language files, with optional client-locale message selection.
- **Optional ProtocolLib integration** — Uses ProtocolLib when available; packet-related checks can use the configured Bukkit fallback.

---

## Why ArgusAC

ArgusAC is designed to make enforcement practical for server operators, not just flag suspicious movement:

- **Predictable recovery** — Movement setbacks use the player's last validated location instead of searching nearby blocks for an improvised landing spot.
- **Operator visibility** — Compatibility status, violation history, replay output, and log-integrity verification help staff understand what the plugin detected and how it responded.
- **Tunable enforcement** — Checks, thresholds, setback modes, exemptions, alerts, and punishment actions can be adjusted for the needs of a server.
- **Graceful version handling** — A compatibility layer can degrade or disable checks when a server version lacks required features, rather than assuming every check behaves identically everywhere.

---

## Installation

### Requirements

- Minecraft server version within the configured target range: **1.12.2–1.21.x**
- **Java 17** runtime
- Spigot or Paper server; some checks may have different capabilities across versions
- Optional: [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) for packet integration

### Install the plugin

1. Download the latest `ArgusAC` JAR from [Releases](../../releases).
2. Put the JAR in the server's `plugins/` directory.
3. Start or restart the server. The plugin creates its default configuration under `plugins/ArgusAC/`.
4. Review `plugins/ArgusAC/config.yml`, then use `/argus reload` to reload the configuration when needed.
5. Run `/argus compat` to inspect the active compatibility status of checks on your server.

---

## Checks

| Check | Category | Purpose |
|-------|----------|---------|
| `ElytraFly` | Movement | Elytra flight analysis with eight independently configurable sub-checks |
| `Speed` | Movement | Ground and water movement speed |
| `Fly` | Movement | Abnormal ascent and prolonged airborne movement |
| `NoFall` | Movement | Suspected falsified ground state and fall-damage behavior |
| `InventoryMove` | Movement | Movement while an inventory is open |
| `Reach` | Combat | Excessive entity attack distance |
| `AutoClicker` | Combat | Click-rate and click-interval patterns |
| `KillAura` | Combat | Suspicious attack rotation and attacks through obstacles |
| `Velocity` | Combat | Modified knockback response |
| `FastBreak` | World | Abnormally fast block breaking |
| `Nuker` | World | Excessive block breaking in a tick |
| `Scaffold` | World | Suspicious automatic block placement patterns |
| `BadPackets` | Packet | Invalid or suspicious movement packet data |

Check behavior and available compatibility features can vary by server version. Use `/argus compat` for the runtime status and see [`config.yml`](src/main/resources/config.yml) for defaults and tuning options.

---

## Setback system

ArgusAC records a player's last known legitimate location after movement passes the configured checks. When a setback is triggered, the plugin can apply one of two modes:

- **`HARD`** — Teleports the player to the recorded location on the main thread and clears velocity and fall distance.
- **`SOFT`** — Corrects the movement destination in the move event for a less abrupt correction.

Setbacks can be enabled globally, configured per check, and adjusted with minimum violation levels. A temporary immunity window after a setback helps avoid repeated flags caused by the correction itself or by latency. If the primary recorded location is obstructed, a previous legitimate location can serve as a fallback.

---

## Commands

<details>
<summary><b>Click to expand / collapse command list</b></summary>

<br>

The main command is **`/argus`** (alias: **`/ac`**, **`/argusac`**).

| Command | Description | Permission |
|---------|-------------|------------|
| `/argus help` | Show the command list | Everyone |
| `/argus reload` | Reload configuration and checks | `argusac.admin` |
| `/argus vl <player>` | View an online player's violation levels | `argusac.admin` |
| `/argus logs <player> [options]` | Query recent logs; supports `--check`, `--since`, `--setback`, and `--page` | `argusac.admin` |
| `/argus history <player> <days> [options]` | Search historical log files; supports `--check`, `--setback`, and `--page` | `argusac.admin` |
| `/argus replay <player> [--side]` | Render a lightweight movement trace | `argusac.admin` |
| `/argus export <player>` | Export a player's audit data as a ZIP | `argusac.admin` |
| `/argus top [--since <1h\|24h\|7d>]` | Show the top 10 violation counts | `argusac.admin` |
| `/argus status` | Show audit queue and log status | `argusac.admin` |
| `/argus verify` | Verify the global log hash chain | `argusac.admin` |
| `/argus debug <check>` | Toggle debug logging for a check | `argusac.admin` |
| `/argus compat` | Show check compatibility and degradation status | `argusac.admin` |
| `/argus toggle <check>` | Toggle a check at runtime | `argusac.admin` |
| `/argus setback <player\|global> <disable\|soft\|hard\|default\|now\|info>` | Manage or inspect setbacks | `argusac.admin` |
| `/argus alerts [on\|off\|status]` | Mute or restore your staff alert messages for this login session | `argusac.alert` |
| `/argus my-data` | View the data-retention summary | Everyone |
| `/argus purge <player>` | Delete a player's stored records and create an audit entry | `argusac.admin` |

</details>

---

## Permissions

<details>
<summary><b>Click to expand / collapse permissions</b></summary>

<br>

| Permission | Default | Description |
|------------|---------|-------------|
| `argusac.admin` | Operators | Use `/argus` administration commands |
| `argusac.alert` | Operators | Receive violation and setback alerts; `/argus alerts` changes personal alert delivery until logout |
| `argusac.bypass` | Nobody | Bypass checks and setbacks |

</details>

---

## Configuration and audit logs

The default configuration is created at `plugins/ArgusAC/config.yml`. It includes language selection, version compatibility behavior, check thresholds, alerts, setback settings, log retention, and privacy options.

By default, setback audit records are written as JSON Lines under `plugins/ArgusAC/logs/setbacks.jsonl`. Audit retention, compression, and privacy settings can be adjusted in the configuration. Review the privacy options and applicable data-retention requirements before enabling or changing player-data logging.

---

## License

MIT License.

---

<a name="chinese"></a>

<p align="center">
  <b>中文</b> | <a href="#english">English</a>
</p>

> 面向 Minecraft 服务器的模块化反作弊插件，提供可配置检测、基于最后合法位置的状态回退，以及可审计的违规记录。

---

## 功能特性

- **模块化检测** — 覆盖移动、战斗、方块交互和数据包检测；各检测项可独立配置与开关。
- **鞘翅检测套件** — 八个可单独配置的子检测项，覆盖速度、上升、悬停、烟花推进、滑翔状态、Timer、频繁切换和免摔滥用。
- **最后合法位置回退** — 移动通过检测后记录合法位置；违规时支持 `HARD` / `SOFT` 两种回退模式、单项覆盖配置和回退后的短暂免疫期。
- **跨版本兼容层** — 目标版本为 Spigot/Paper 1.12.2–1.21.x，并报告检测项的降级或不可用状态；不同服务端版本和 API 下检测能力可能不同。
- **可调的拦截与告警** — 可设置违规阈值、回退策略、世界豁免、管理员通知和惩戒命令。
- **审计与日志工具** — 查询近期及历史记录、导出玩家审计数据、查看移动轨迹，并校验 SHA-256 哈希链。
- **多语言支持** — 内置 `zh_cn` 与 `en_us` 语言文件，并可按玩家客户端语言选择消息。
- **可选 ProtocolLib 集成** — 有 ProtocolLib 时可使用数据包集成；缺少时可按配置回退到 Bukkit 事件处理。

---

## 为什么选择 ArgusAC

ArgusAC 不只标记可疑行为，也注重让服务器管理者能够理解和维护反作弊措施：

- **回退行为可预测** — 违规移动会回到最后验证通过的位置，而不是临时搜索附近方块作为落点。
- **检测过程可追溯** — 兼容性状态、违规历史、轨迹回放和日志完整性校验，帮助管理员了解检测结果及后续处理。
- **惩戒方式可调** — 检测项、阈值、回退模式、豁免、告警和惩戒动作均可按服务器需求配置。
- **按版本处理能力差异** — 当旧版服务端缺少所需功能时，兼容层可以降级或禁用相关检测，而不假设所有版本上的行为完全一致。

---

## 安装

### 环境要求

- Minecraft 服务端版本在项目配置目标范围内：**1.12.2–1.21.x**
- **Java 17** 运行环境
- Spigot 或 Paper 服务端；不同版本的检测能力可能不同
- 可选：[ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) 数据包集成

### 安装插件

1. 从 [Releases](../../releases) 下载最新的 `ArgusAC` JAR。
2. 将 JAR 放入服务端的 `plugins/` 目录。
3. 启动或重启服务端；插件会在 `plugins/ArgusAC/` 下生成默认配置。
4. 检查 `plugins/ArgusAC/config.yml`，之后可使用 `/argus reload` 重载配置。
5. 使用 `/argus compat` 查看当前服务端上的检测兼容状态。

---

## 检测模块

| 检测项 | 分类 | 说明 |
|--------|------|------|
| `ElytraFly` | 移动 | 鞘翅飞行分析，包含八个可独立配置的子检测项 |
| `Speed` | 移动 | 陆地与水面移动速度 |
| `Fly` | 移动 | 异常上升与长时间滞空 |
| `NoFall` | 移动 | 疑似伪造接地状态与摔落伤害行为 |
| `InventoryMove` | 移动 | 打开物品栏时移动 |
| `Reach` | 战斗 | 过远距离攻击实体 |
| `AutoClicker` | 战斗 | 点击频率与点击间隔模式 |
| `KillAura` | 战斗 | 可疑攻击视角与隔墙攻击 |
| `Velocity` | 战斗 | 击退响应异常 |
| `FastBreak` | 方块 | 异常快速破坏方块 |
| `Nuker` | 方块 | 单个 Tick 内破坏过多方块 |
| `Scaffold` | 方块 | 可疑的自动搭方块行为 |
| `BadPackets` | 数据包 | 非法或可疑的移动数据包 |

检测行为与兼容能力可能因服务端版本而异。可使用 `/argus compat` 查看运行状态，并在 [`config.yml`](src/main/resources/config.yml) 查看默认值和可调整参数。

---

## 状态回退系统

ArgusAC 会在玩家移动通过配置的检测后记录其最后合法位置。触发回退时支持以下模式：

- **`HARD`** — 在主线程将玩家传送回已记录位置，并清空速度与坠落距离。
- **`SOFT`** — 在移动事件中修正移动终点，减少突兀的回弹感。

可全局启用或关闭回退，也可按检测项设置覆盖规则和最低违规等级。回退后会进入临时免疫期，避免坐标修正或网络延迟导致重复触发。如果主要记录位置被方块阻挡，也可以使用先前的合法位置作为备用点。

---

## 命令

<details>
<summary><b>点击展开 / 折叠命令列表</b></summary>

<br>

主命令为 **`/argus`**（别名：**`/ac`**、**`/argusac`**）。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/argus help` | 显示命令列表 | 所有人 |
| `/argus reload` | 重载配置与检测模块 | `argusac.admin` |
| `/argus vl <玩家>` | 查看在线玩家的违规等级 | `argusac.admin` |
| `/argus logs <玩家> [选项]` | 查询近期记录；支持 `--check`、`--since`、`--setback`、`--page` | `argusac.admin` |
| `/argus history <玩家> <天数> [选项]` | 检索历史日志；支持 `--check`、`--setback`、`--page` | `argusac.admin` |
| `/argus replay <玩家> [--side]` | 输出轻量移动轨迹 | `argusac.admin` |
| `/argus export <玩家>` | 将玩家审计数据导出为 ZIP | `argusac.admin` |
| `/argus top [--since <1h\|24h\|7d>]` | 显示违规次数前十名 | `argusac.admin` |
| `/argus status` | 查看审计队列与日志状态 | `argusac.admin` |
| `/argus verify` | 校验全局日志哈希链 | `argusac.admin` |
| `/argus debug <检测项>` | 开关指定检测项的调试日志 | `argusac.admin` |
| `/argus compat` | 查看检测项兼容与降级状态 | `argusac.admin` |
| `/argus toggle <检测项>` | 运行时开关检测项 | `argusac.admin` |
| `/argus setback <玩家\|global> <disable\|soft\|hard\|default\|now\|info>` | 管理或查看状态回退 | `argusac.admin` |
| `/argus alerts [on\|off\|status]` | 本次登录期间关闭、恢复或查看个人管理员告警提示 | `argusac.alert` |
| `/argus my-data` | 查看数据保留策略摘要 | 所有人 |
| `/argus purge <玩家>` | 删除玩家存储记录并生成审计记录 | `argusac.admin` |

</details>

---

## 权限

<details>
<summary><b>点击展开 / 折叠权限节点</b></summary>

<br>

| 权限 | 默认 | 说明 |
|------|------|------|
| `argusac.admin` | OP | 执行 `/argus` 管理命令 |
| `argusac.alert` | OP | 接收违规与回退告警；`/argus alerts` 可关闭本次登录期间的个人提示 |
| `argusac.bypass` | 无人 | 豁免检测与状态回退 |

</details>

---

## 配置与审计日志

默认配置文件会生成在 `plugins/ArgusAC/config.yml`，包含语言、版本兼容策略、检测参数、告警、状态回退、日志保留和隐私选项。

默认的回退审计记录以 JSON Lines 格式写入 `plugins/ArgusAC/logs/setbacks.jsonl`。日志保留、压缩和隐私选项均可在配置中调整。启用或修改玩家数据记录前，请确认相关隐私设置及数据保留要求。

---

## 许可证

MIT 许可证。
