# 更新日志

本文件记录本项目所有重要变更。

本文件的格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
并且本项目遵循 [语义化版本规范](https://semver.org/lang/zh-CN/)。

## [1.2.1] - 2026-10-24

### 新增
- **Netty 数据包层战斗拦截体系 (`PacketPipelineManager` & `CombatMitigationHandler`)**：将核心攻击阻断逻辑彻底由 Bukkit 事件层下沉至 Netty 数据包层（PacketEvents），在接收到客户端攻击包（`PacketPlayInUseEntity` / `ServerboundInteractPacket`，Action 为 `ATTACK`）的瞬间即时拦截，彻底阻断其流入服务端主线程及 NMS 伤害结算管线。
- **协议进包阶段合规性初筛引擎**：在 Netty 进包管道内完成无锁极速初筛与即时丢弃：
  - `KillAuraCheck`：视角夹角（Angle）、背身盲区（Blindspot）、射线穿墙碰撞遮挡（Raytrace）、突变自瞄（Aimbot Snap）与极速多目标切换（Multi-Target Switch）；
  - `ReachCheck`：基于历史实体包围盒快照的延迟补偿极限物理距离（GrimAC-Grade Lag Compensated Reach）；
  - `MaceCheck`：伪造下落高度（MaceDMG）、突破运动学重力加速度极限与高频连砸；
  - `AutoClickerCheck`：纳秒级发包打点、极限 CPS 超限与机械均匀点击间隔标准差。
- **下行动态实体状态包抑制 (`PacketPlayOutEntityStatus` / `ClientboundEntityEventPacket`)**：拦截发往下游客户端的目标受害实体状态包（`Status = 2`, `33`, `36`, `37` 受伤发红/抖动与 `Status = 3` 死亡动画），彻底解决攻击被阻断后受害者仍出现异常发红抖动或虚假死亡的视觉穿透问题。
- **挥手与攻击音效广播消除**：在战斗抑制窗口内拦截派生的挥手广播包（`PacketPlayOutAnimation` / `ClientboundAnimatePacket`）、受害实体粒子动画以及服务端生成的受击/暴击/横扫命名音效包（`PacketPlayOutNamedSoundEffect` / `ClientboundSoundPacket`），实现完全纯净的静默拦截。
- **分级处置策略 (`CombatVerdict`)**：
  - `LOW_CONFIDENCE_DROP`（低确信/微量超出）：如 Reach 超出在 `0.25` 格缓冲裕度内、Angle 超出在 `6.0°` 裕度内，仅在 Netty 底层静默丢弃（Drop）攻击包，不增加严重 VL，防止网络抖动导致的误伤；
  - `HIGH_CONFIDENCE_VIOLATION`（高确信/明显作弊）：丢弃数据包并累加 VL；若同时伴随移动异常（移动缓冲区累积异常、物理预测器残差偏高或处于空中违规状态），自动联动移动预测引擎与 `SetbackManager` 触发标准位置矫正（Setback），达到阈值后自动执行踢出或封禁。
- **重锤战斗与下落伤害异常全方位防御 (`MaceCheck` / `MaceDMG`)**：针对 Minecraft 1.21+ 全新重锤武器及其主流外挂（Wurst MaceDMG、LiquidBounce MaceKill、FakeFall 伪造下落）建立确定性检测与拦截体系。
- **物理真最高点坠落校验 (True Apex Verification)**：独立追踪玩家离开地面以来的物理真最高点（`trueApexY`），严格约束声称下落高度不可超越物理几何落差，彻底瓦解平地伪造高空坠落包。
- **地面虚假重击当场阻断 (Ground Smash Interception)**：严查在地面接地状态或起跳物理落差 $< 1.5$ 格时触发的重锤重击伤害，根除平地一击秒杀（OHKO）作弊。
- **运动学下落速度与耗时约束 (Kinematic Fall Constraint)**：基于滞空 Tick 数强加终端下落极速约束（$D_{\max} \approx \text{airTicks} \times 3.92 + 2.0$），拦截 1~2 tick 内突发性伪造下落数十格的数据包轰炸。
- **理论伤害天花板与硬上限双重防护 (Damage Ceiling & Cap)**：严密推算重锤基础伤害、致密（Density I~V）、力量药水加成与暴击加成的物理极限，支持配置单次伤害硬上限与 CANCEL / NERF 双重处置模式。
- **重击频率限制 (Smash Rate Limiting)**：设定连续重击最小间隔（默认 350ms），防御利用数据包连点实现原地连续秒杀。
- **多版本自适应降级矩阵**：在 1.21 以前版本服务器自动静默挂起该模块（DISABLED），在 1.21~26.3 环境满血运行（FULL）。

### 变更
- **战斗拦截架构解耦重构**：将主要的取消逻辑由 Bukkit 事件层移至 Netty 数据包拦截器，由 `CombatMitigationHandler` 集中统一调度数据包丢弃、出包抑制与处置升级。
- **线程安全实体历史索引**：在 `EntityHistoryTracker` 中扩展了双向 `EntityId <-> UUID` 映射索引与视角高度/死亡状态快照，支撑 Netty 管道线程无锁高效查询目标实体。
- **Bukkit 事件层降级为最终兜底防线**：`PlayerEventListener` 中的 `EntityDamageByEntityEvent` 监听降级为兜底防御（Fallback），仅用于处理未命中缓存的实体或外部插件触发的兼容事件，正常情况下违规包在协议层已被丢弃，该事件根本不会被触发。

## [1.2.0] - 2026-10-23

### 新增
- **负数 Teleport ID 绝对二进制隔离**：回退包强制采用与 GrimAC 源码一致的 `random.nextInt() | Integer.MIN_VALUE` 负数 ID 体系，与原版服务端及常规插件自增的正整数 ID 形成天然物理隔离，杜绝客户端伪造与时序污染。
- **丢弃回退包逃避行为即时补刀重同步**：对标 GrimAC `checkTeleportQueue` 与 `BadPacketsN` 机制，在 In-Flight 传输态下追踪未确认移动包计数；一旦发现客户端故意装聋作哑丢弃回退包企图继续向前蠕动，立即重新补刀推送位置同步包并硬清空动量。
- **数据包底层坐标硬覆盖**：在 `PacketPipelineManager` 协议最底层，对于传输态期间偏离目标点的移动数据包，直接将其内部 X/Y/Z 坐标硬重写为锚点坐标，绝不留任何蠕动穿透空间。
- **载具强制脱离与坐骑同步**：严格对齐 GrimAC 载具回退机制，在玩家违规时强制将其弹出下马（`player.leaveVehicle()`），清除载具速度并拉回合法位置，彻底封死船飞（BoatFly）或马匹骑乘客户端插值蹭步漏洞。
- **回退期间动作抑制 (`SetbackBlocker`)**：在 In-Flight 传输态与违规锁定期间，在协议底层直接阻断 `INTERACT_ENTITY`、`ATTACK` 与 `PLAYER_DIGGING` 数据包，严防利用回退微延迟抽刀伤人或造成幽灵方块。
- **权威安全锚点守恒法则**：引入违规锁定锚点机制（Locked Anchor），连续违规期间回退目标绝对锁定在违规前的原始合法基准点，绝不随客户端偷跑向前漂移。
- **协议级速度硬清零与防误判**：在下发位置同步包的同时向玩家管道下发 `WrapperPlayServerEntityVelocity(0, 0, 0)` 零速度数据包，打灭客户端残余按键惯性；同时在底层重置速度预期，防止误将回退零速度包当成击退刷新免检时间。
- **FastEat 快速进食防御**：引入服务端 Tick 级与毫秒时间差食用计时器（32 ticks / 1600ms 基准，4 ticks 抖动容限）、伪造完成数据包拦截（无交互记录 Instant Eat 直接阻断）、动态 Ping RTT 延迟补偿与物品手持刷写同步。
- **FastBow 快速射箭防御**：引入服务端 Tick 级蓄力计时器（20 ticks / 1000ms 基准，2 ticks 抖动容限）、1000ms 滑动窗口防堆包轰炸、全局射击冷却窗（>=250ms）与软绵箭/静默取消防御策略。
- **双层战斗静默拦截**：统一在 Bukkit 事件与 PacketEvents 协议层双重拦截非法攻击与近战伤害，伤害与击退彻底静默归零。
- **鞘翅滑翔全面防御**：引入机械能守恒约束（$E=mgh+\frac{1}{2}mv^2$）、实时胸甲耐久强检（耐久 $\le 1$ 即刻剥离）与定高平飞巡航拦截。
- **冰面大跳起飞空中惯性记忆 (`airborneFromIce`)**：引入冰面起跳空中惯性标记，离开冰面 4 tick 后只要玩家仍处于当前跳跃的空中滞空中，持续保留冰面起跳加成，直至玩家安全落在非冰方块表面。
- **独立检测配置 (`checks.yml`)**：全部 15 个检测项独立解耦至 `checks.yml`，支持 `/argus reload` 动态热加载。

### 变更
- **全面对标 GrimAC 回退机制，根除蹭步蠕动漏洞**：升级回退状态机，对齐 GrimAC 预测失步模型，消除连续违规偷跑空间。
- **严重超速直通拦截与平滑缓冲校准**：科学化瞬发硬拦截阈值（`diff >= 0.18 || distance > limit * 1.50`），将默认平滑缓冲区 `speed-buffer-max` 由 0.40 收紧至 0.12。
- **违规锁定期微容限平稳化**：处于 2000ms 违规锁定期内，`SpeedCheck` 缓冲区上限动态收紧至 0.04 格，既杜绝蹭步偷跑又防止浮点微差死循环拉回。
- **疾跑下楼梯/台阶 (Step-down) 动量补偿升级 (1.30x)**：检测向下位移（`fallDelta >= 0.12`，如下台阶 0.16 格），赋予 1.30x 动量容限（上限达 0.4225 格），彻底解决原版疾跑下楼梯撑爆缓冲区误报。
- **连续起跳与刚着陆连跳 (Bunnyhop) 落地惯性缓冲**：激活并精准维护 `groundTicks`，在空中疾跑刚着地的 1~2 帧（`groundTicks <= 2`）赋予 0.39 格冲量容限，包容原版落地瞬间保留的空中末速度。
- **空中受击/击退滞空智能延续 (1000ms+)**：将通用受击/击退时效由 500ms 放宽至 1000ms，且在空中未落地前（`!isOnGround || airTicks > 0`）自动延续 500ms，杜绝空中飞到半程突然被当成超速拉回。
- **飞行与滞空超敏提速**：`FlyCheck` 非法起飞初速收紧至 0.48，空中平飞悬停判定阈值从 4 tick 降至 2 tick 瞬发拉回，`max-air-ticks` 由 25 降至 12 tick。
- **水面行走当帧阻断 (`JesusCheck`)**：在虚假接地、水面平飞及超速时显式加入当帧事件取消，并将平飞与水面停留判定阈值分别收紧至 2 tick 和 5 tick（0.25秒）。
- **协议层 Timer 丢包与调度回退**：Timer 漂移突破赤字限制时在底层直接丢弃超频 Flying 包并调度回退，将赤字容限科学放宽至 -150.0ms 抵御网络抖动。
- **快照队列防超速污染**：处于超速嫌疑状态（`speedBuffer > 0.04`）的位置严禁写入合法地面快照，防止回退点前移。
- **Bukkit 事件层 In-Flight 彻底阻断**：在传输态期间未对齐目标点的移动事件显式执行 `event.setCancelled(true)`，杜绝事件向后渗透。
- **回退引擎模块化解耦**：将原单体 `SetbackManager` 拆分为 `SafeLocationFinder`、`SetbackPhysics`、`SetbackPacketSync`、`SetbackNotifier`、`PlayerSetbackContext` 等子模块。
- **Minecraft 26.3 版本标准对齐**：统一 `ServerVersion` 枚举（`V26_0`、`V26_3`）、解析正则、`plugin.yml`、`README.md` 与 `.gitignore` 版本描述。

### 移除
- **拔除 SpeedCheck「平滑截断放行」代码**：彻底移除 `SpeedCheck` 中为超速玩家裁剪并补齐 `clampedTo` 的逻辑，违规超速时坚决原地取消事件，杜绝帮助作弊者按住 W 键持续向前蹭步。
- **移除侵入式战斗抑制**：移除战斗检测中的侵入式挥刀缴械与武器冷却清零逻辑，保障纯正原版 PvP 手感。
- **废除移动回退物理冷却**：彻底移除 350ms 冷却时间限制，只要移动违规连续每个 Tick 均当场硬拦截阻断，消灭作弊者利用 7-Tick 冷却窗口蹭步偷跑漏洞。

### 修复
- **跳跃最高点与台阶跳跃 (Step-up Jump) 误报消除 (`FlyCheck`)**：将起跳首帧上升上限放宽至合法的 `1.05 + 药水加成` 格，将升空中期递减包络线完整扩展覆盖至第 6 Tick，第 7 Tick 起方才禁止持续向上拔高，根除最高点与半砖台阶跳跃误报。
- **脚底方块探针深度与范围校准**：将 `hasBlockBelow` 的默认探查深度恢复至原版台阶物理高度标准的 0.60 格，覆盖特殊方块表面。
- **服务端主动重力监管平地误报 HARD 回退根治 (`SetbackManager`)**：将单点探针全面升级为 5 点 AABB 实体探针加 0.60 格深度和 `isStandable` 校验，增加 `data.isOnGround()` 与 `airTicks <= 0` 双重门锁，断包悬停阈值放宽至 1000ms。
- **协议层 Timer 抖动缓冲与回退同步时钟重置**：在所有回退出口（`directCancel`、`executeHardSetbackOnMainThread` 与 `SetbackNotifier`）全面增加 `timerTracker.reset()`，杜绝回退后二次误报。
- **预填装弩箭防误报优化 (`FastBowCheck`)**：为预先填装好的弩提供蓄力 Tick 打点豁免，根除预装填弩箭射击误判。
- **创造模式连点防误报 (`AutoClickerCheck`)**：在连点检测入口增加 `CREATIVE` 与 `SPECTATOR` 模式显式豁免，消除飞搭与拆方块误报。
- **NoFall 地形落脚点防误报 (`PacketPipelineManager`)**：增强协议层虚假地面判定，加入半方块、地毯、台阶、雪层、陷阱门与脚手架等承托面扫描。
- **击退响应 (Velocity / Anti-KB) 逻辑倒置修复**：引入基于玩家网络 Ping 的往返响应窗口，合规位移即刻清除判定，杜绝下发击退后首帧误判。
- **水面跳跃海豚跳与浮水游泳误报 Fly 及连续下压沉底根治 (`FlyCheck` & `SetbackManager`)**：彻底重构流体环境判定，消除原先“脚和头必须同时深潜水中”导致水面露头游泳踩水或水面跳跃（Dolphin Hop）被误判为空中滞空的缺陷；并在 `SetbackManager` 回退执行中对流体环境显式豁免真空重力加速度下坠模拟，彻底杜绝水面跳跃误判以及被连续下压拉回沉入水底的死循环。
- **奔跑跳跃 (Sprint-Jump) 与连续跳跃误报 Speed 根治 (`SpeedCheck`)**：
  - 修正起跳首帧的时序倒置问题，在起跳上升阶段（`ΔY > 0.03`）直接接入起跳初速度包络线，给予 0.44 格充足物理容限，完美吸收原版向前跳跃冲量（`0.36~0.38` 格/tick）；
  - 将平地疾跑基准由 0.325 微调至 0.340（覆盖 45 度 Strafe 疾跑），连跳落地冲量放宽至 0.40 格；
  - 将常规平滑缓冲区上限 `speed-buffer-max` 由 0.12 优化放宽至成熟反作弊标准的 0.25（衰减率提升至 0.10），充分平滑连续跳跃离散物理方差，同时保留 `diff >= 0.18 || distance > limit * 1.50` 直通硬拦截与违规锁定期 0.04 收紧容限，确保真作弊 0.15 秒内定身。
- **锁定锚点 2000ms 守恒修复**：修复移动 400ms 未取消即提前清除锁定锚点的漏洞，严格焊满整整 2000ms 锁定周期。

---

## [1.1.4] - 2026-10-17

### 修复 (Fixed)
- **当帧即时直接取消与重力拟合**：废除回退自免检窗口，采用当帧事件内直接取消与原版自由落体加速度仿真（$v_Y = (v_{Y,prev} - 0.08) \times 0.98$）。
- **半方块精细贴合与载具同步**：精准贴合台阶（0.5格）、雪层与楼梯真实表面；玩家与坐骑同步传送。
- **主动重力监管引擎**：主线程周期巡检并强制下压客户端断包悬浮（Blink/Freecam）。
- **模拟滑冰加速漏洞修复**：严格限制非冰地面平跑极速（$\le 0.31$），脱冰惯性收紧至 4 Tick。
