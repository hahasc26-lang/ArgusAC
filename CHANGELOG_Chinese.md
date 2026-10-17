# 更新日志

本文件记录本项目所有重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并遵循 [语义化版本规范](https://semver.org/lang/zh-CN/)。

## [1.1.1] - 2026-10-17

### 修复与增强 (战斗防御响应强化与移动误报彻底消灭)

- **杀戮光环 (KillAura) 主动防御与战斗缴械机制**：
  - 建立全链路四重实战反应手段：伤害与击退彻底拦截、攻击者蓄力充能清零 (`resetAttackCooldown`)、连续违规激活战斗抑制与缴械惩罚 (`Combat Throttle / Disarm`，1.5 秒内静默抹除所有近战伤害并伴随警示音效)。
  - 补充背身盲区攻击拦截 (视线夹角 > 90° 攻击身后目标) 与攻击瞬间自瞄突变偏转检测 (`snap-check`)。
  - 修复多目标极速切换 (`Rapid Switch`) 遗漏取消攻击伤害事件的致命漏洞。
- **移动速度 (Speed) 正常移动误报彻底消除**：
  - 将单点接地检测重构为碰撞箱底面 5 点采样与多版本可站立方块特性识别 (`isStandable`)，彻底解决玩家踩在地毯、雪片、台阶、楼梯与方块边缘悬空误判为在空中的缺陷。
  - 修复空中加速度检测 (`AirAccelerate`) 粗暴抹杀疾跑跳跃起跳合法 0.2 水平冲量加成的逻辑缺陷 (`airTicks <= 5`)，并补充冰面滑行起跳长程惯性记忆 (`iceTicks`)。
  - 物理预测引擎解耦：水平速度检测严格仅根据水平残差有效性 (`isHorizontalValid`) 判定，杜绝垂直走楼梯/台阶/下坡连带引发 Speed 误报。
  - 引入可配置速度违规缓冲区 `Speed Violation Buffer` (`speed-buffer-max: 0.25`)，平滑吸收丢包抖动，并增加天花板压头疾跑跳跃 (1.35x) 容差。
- **自动连点器 (AutoClicker) 重复计数翻倍误报修复**：
  - 消除网络层实体交互包与 Bukkit 主线程多事件重叠打点造成的 CPS 翻倍失真，统一收口至网络层 `ANIMATION` 原生包；防抖窗口提升至 30ms。
- **跨版本全兼容性与崩溃修复**：
  - 消除 `VelocityCheck`、`NoFallCheck` 与 `ElytraFlyCheck` 中对高版本 Material 枚举 (`COBWEB`, `SCAFFOLDING` 等) 的直接字面引用，杜绝 1.12.2 类加载报 `NoSuchFieldError` 致命错误。
  - 在 `VersionAdapter` 中封装 `getBlockHardness(Block)` 并对 `Bukkit.getCurrentTick()` 提供安全兼容降级，消灭 `FastBreakCheck` 和 `NukerCheck` 在低版本的 `NoSuchMethodError`。
  - 为 `InventoryMoveCheck` 增加冰面滑行惯性豁免，消除冰面开箱滑行误报。

## [1.1.0] - 2026-10-17

### 重大架构升级 (GrimAC-Grade Architecture Evolution)

- **PacketEvents 2.7.0 原生内置集成**：彻底移除了对外部 ProtocolLib 插件的依赖，改用内置嵌入与包名重定向（Shaded Relocation），实现 Spigot/Paper 1.12.2 ~ 1.21.x 零前置依赖开箱即用。
- **确定性移动模拟预测引擎 (Prediction Simulation)**：
  - 基于 Minecraft 原版物理运动学方程，穷举客户端 8 向按键输入组合、疾跑/走动/潜行、跳跃冲量与垂直重力方程。
  - 仿 GrimAC 工业级标准引入理论包络残差 (Residual) 与缓冲累积 (Buffer) 判定，彻底替代传统粗糙位移阈值比对，精准抓取微速 (Mini-Speed) 与慢速微跳 (Mini-Jump)。
- **实体历史碰撞盒回溯与延迟补偿 (Lag Compensated Entity History Tracker)**：
  - 维护目标实体近 1000ms 内的每 tick 三维轴对齐包围盒 (AABB) 历史快照。
  - 攻击发生时根据攻击者真实 Ping 往返延迟，精准回溯至攻击者视角所见的历史碰撞盒，彻底消灭目标被击退后退引发的虚假超距误报，将 Reach 判定严格收敛至原版理论极限 (3.00 格)。
- **协议层 Timer 变速时钟平衡引擎 (Clock Drift Engine)**：
  - 在数据包底层引入每 tick 50ms 的纳秒级时钟银行 (Balance) 算法，精准捕捉 1.05x ~ 2.0x 变速齿轮、快速进食与包频轰炸。
- **战斗数据包流水线 (Combat Packet Pipeline)**：
  - 在底层网络直接监听实体交互攻击包 (Interact Entity) 与挥臂动作包 (Animation)，彻底消除 Bukkit 主线程 tick 合并对连点器 (AutoClicker) CPS 与方差采样的失真影响。
- **跨版本安全视线射线遮挡判定**：
  - 在 `VersionAdapter` 中抽象多态射线检测，在 1.13+ 调用原生 `world.rayTraceBlocks`，在 1.12.2 调用安全步进光线投射，彻底杜绝低版本 `NoSuchMethodError` 崩溃风险。

## [1.0.0] - 2026-10-17

### 新增 / Added

- **模块化反作弊检测**：新增可配置的移动、战斗、方块交互与异常数据包检测，包括 Speed、Fly、NoFall、InventoryMove、Reach、AutoClicker、KillAura、Velocity、FastBreak、Nuker、Scaffold 和 BadPackets。
- **鞘翅飞行检测套件**：新增八个可配置子检测项，覆盖飞行速度、异常上升、空中悬停、烟花推进、滑翔状态一致性、Timer、频繁切换滑翔状态与免摔滥用。
- **最后合法位置状态回退**：新增 `HARD` 与 `SOFT` 回退模式、单项检测覆盖设置、备用合法位置、可配置免疫期及回退通知。
- **违规管理与告警**：新增违规等级跟踪与衰减、可配置管理员告警和惩戒动作。
- **审计日志与调查工具**：新增异步 JSON Lines 审计日志、日志保留与压缩、SHA-256 哈希链校验、近期及历史日志查询、玩家数据导出和移动轨迹回放。
- **跨版本兼容层**：新增面向插件目标 Bukkit/Spigot/Paper 服务端版本的兼容性检测与优雅降级，并可通过 `/argus compat` 查看运行状态。
- **多语言支持**：新增英文与简体中文语言文件，并支持按玩家客户端语言选择消息。
- **管理命令**：新增 `/argus`（别名 `/ac`、`/argusac`）命令，支持配置重载、检测管理、违规查询、日志审查、兼容性诊断、回退控制与审计数据管理。
