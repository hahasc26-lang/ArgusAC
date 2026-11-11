# 更新日志

本项目的重大变更均记录于此文件中。

本更新日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本 (Semantic Versioning)](https://semver.org/lang/zh-CN/) 规范。

> **Language / 语言切换**:  
> [English Version](#english-changelog) | [中文版本 (Chinese Version)](#更新日志)

---

## [1.2.3] - 2026-11-10

### 新增
- **模块化回退子系统与执行器解耦重构 (`com.example.argusac.setback.handler`)**：
  - 将原单体庞杂的 `SetbackManager` 核心回退逻辑全面拆分解耦至独立的模式处理器，统一实现 `SetbackHandler` 接口：
    - `CancelSetbackHandler`：负责 `CANCEL` 模式当帧阻断（`moveEvent.setCancelled(true)`）、二进制负数 Teleport ID 隔离（`Integer.MIN_VALUE`）、锁定基准锚点（`lockedAnchorLocation`）防蠕变去同步、强制弹出载具并消除动量，以及方块表面精准贴合与重力加速度防穿模。
    - `SoftSetbackHandler`：负责 `SOFT` 模式在移动事件内平滑改写目标坐标（`moveEvent.setTo(safeTo)`），不触发主线程强制瞬移拉扯，锁定水平位移并拟合原版自由落体加速度。
    - `HardSetbackHandler`：负责 `HARD` 模式在已验证合法历史快照池（`LegitimateLocation Snapshots`）中检索有界坐标、下落与动量重置、主线程 `player.teleport` 强制传送及网络校准序列（`PendingCorrection`）发号。
  - 新增配套单元测试类 `SetbackModeHandlersTest`。

### 修复
- **快速射箭 (`FastBowCheck`) Netty 异步异常、实体瞄准漏记、方块交互与单发无限取消死锁彻底根除**：
  - 修复了 `PacketPipelineManager#handleIncomingUseItemPacket` 在 Netty 异步网络线程中直接调用 `player.getInventory()` 导致在 Paper 1.16+ ~ 1.21+ 上 100% 触发 `AsyncCatcher` 异常（`IllegalStateException: Asynchronous PlayerInventory access!`）并被静默吞掉、使数据包层蓄力打点完全瘫痪的致命缺陷。重构为通过 `Bukkit.getScheduler().runTask` 调度到主线程安全执行，并将拦截范围扩充至 `USE_ITEM`（空气使用）与 `PLAYER_BLOCK_PLACEMENT`（方块右键）。
  - 在 `PlayerInteractListener` 中补全 `PlayerInteractEntityEvent` 与 `PlayerInteractAtEntityEvent` 实体交互监听，彻底解决玩家准星对准实体（怪物、动物、村民、盔甲架或其他玩家）拉弓瞄准时不触发 `PlayerInteractEvent` 导致蓄力起始点 100% 漏记的严重缺陷。
  - 修复了 `FastBowCheck#handleShootBow` 中对于未记录到起始点（`startTick <= 0`）的单次正常射击一刀切判定为 `Fast Charge` 违规并取消射击、导致正常玩家一旦漏一次打点就陷入“无论怎么蓄力都射不出一支箭”的无限取消死锁问题。重构为仅在短时间连射（`(now - lastShotTime) < 1000ms`）或窗口内多发（`shotsInWindow >= 2`）时坚决阻断外挂瞬发，对孤立单发射击实现自愈放行。
  - 在 `FalsePositiveRegressionTest` 中补充针对 FastBow 孤立单发防误杀与连发瞬射强拦截的回归测试 `testFastBowBurstVsIsolatedShotProtection`。
- **快速射箭与快速进食 (`FastBowCheck` / `FastEatCheck`) 松弦时序、装填弩、副手进食与 TPS 波动误报修复**：
  - 修复了 `PacketPipelineManager#handleIncomingPlayerDiggingPacket` 中在 Netty 线程收到 `RELEASE_USE_ITEM` 时直接调用 `data.stopBowCharge()` 导致在服务端主线程触发 `EntityShootBowEvent` 之前将 `bowChargeStartTick` 与 `bowChargeStartTimeMs` 提前清零，引发正常拉弓射箭 100% 误报 `FastBow (Combat)` 的关键时序问题。引入 `PlayerData#releaseBowCharge` 与 `PlayerData#releaseEating`，仅解除防抖锁定而不抹除蓄力时间戳，交由主线程核验完成后再行清零。
  - 在 `FastBowCheck#handleBowStart` 中新增已装填弩（`Charged Crossbow`）检测（`FastBowCheck#isCrossbowCharged`），避免射击已上膛弩时将蓄力起点覆盖为当前帧导致 `elapsed = 0t/0ms` 误报；并将原普通弓 `force < 0.8f` 线性估算替换为原版弓力二次函数的精确反解算法（`FastBowCheck#calculateExpectedBowChargeTicks` 与 `calculateExpectedBowChargeMs`）。
  - 修复了 `PlayerInteractListener#onPlayerInteract` 中主手持有无箭矢的弓/弩、副手吃食物时跳过副手起吃打点，导致进食完成时 100% 误判 `Instant Consume` 的问题。
  - 新增 `FastEatCheck#isFastEatViolation`，将单向 `||` 判定升级为 Tick 与毫秒耗时联合校验配合 `< 75%` 极端短耗时硬拦截，并将 `PlayerData#startEating`（`3500ms`）与 `PlayerData#startBowCharge`（`15000ms`）防抖窗口延长，彻底消除服务器 TPS 波动与长时间满弦瞄准下的误报。
- **鞘翅滑翔 (`ElytraFlyCheck` / `ElytraFirework`) 俯冲拉平与余速滑翔误报修复**：
  - 为 `ElytraFirework` 补充起跳宽限期（`!inTakeoffGrace`）、俯冲拉平垂直动量转化豁免（`deltaY >= -0.05 && lastDeltaY >= -0.05`）以及俯冲峰值后自然减速滑翔识别（`ElytraFlyCheck#isDecayingPostDiveGlide`），解决高空俯冲后平飞滑翔被误判为无烟花加速的问题。
- **反击退检测 (`VelocityCheck`) 网络抖动与减速阻滞方块误报修复**：
  - 引入 `250ms` 网络抖动观测窗口（`maxWaitTime = Math.min(1000L, minWaitTime + 250L)`）与 `1500ms` 陈旧击退过期保护。
  - 新增蜘蛛网（`WEB`）、甜浆果丛（`BERRY_BUSH`）、细雪（`POWDER_SNOW`）、蜂蜜块、灵魂沙、粘液块、气泡柱、水体及邻近攀爬物的击退豁免。
- **快速挖掘 (`FastBreakCheck`) 挖掘疲劳动态阈值与连续挖掘计时修复**：
  - 新增 `FastBreakCheck#calculateMiningFatigueThresholdMs`，综合挖掘疲劳等级、工具效率附魔（Efficiency）、急迫效果（Haste）与 `time-leniency` 动态计算耗时底线，解决持高效率镐在挖掘疲劳 I 下挖掘普通矿石被一刀切误判的问题。
  - 修复 `PlayerData#startDigging` 在连续挖掘期间覆盖首次 `diggingStartTime` 导致挖掘计时被截短误判的问题。
- **潜行搭桥边缘接地判定与栅栏/石墙高度对齐 (`PlayerMoveListener` & `SafeLocationFinder`)**：
  - 将 `PlayerMoveListener#isGrounded` 升级为支持潜行探出半径（`0.42` 格）的 9 点探针，消除潜行搭桥边缘触发 `NoFall Packet GroundSpoof` 误报。
  - 在 `SafeLocationFinder#isGrounded` 中补充栅栏（`FENCE`）、石墙（`WALL`）与竹子（`BAMBOO`）的 1.5 格高碰撞箱识别。
- **异常数据包 (`BadPacketsCheck`) 状态切换宽容与冗余分支清理**：
  - 为 `Sneak-Sprint` 冲突状态检测增加水平移速门槛（`horizDist > 0.20`），并移除不可达且会误判原版静止保活包的重复位置包分支。

### 优化与清理
- **离线玩家上下文内存泄漏修复与冗余代码清理**：
  - 修复 `PlayerLifecycleListener#onPlayerQuit` 调用 `getContext(uuid).clearLockedAnchor()` 导致玩家退出后在 `SetbackManager#contextMap` 中重新创建残留空实例的内存泄漏问题；统一改用 `removeContext(uuid)` 并在只读路径使用 `getExistingContext(uuid)`。
  - 将 `clearLockedAnchor()` 内聚至 `PlayerSetbackContext#reset` 与 `clear()`，移除监听器中的重复重置逻辑及未使用的死代码 `PunishmentManager#executeDefaultPunishment`。
  - 新增 `SetbackOverride.CANCEL_ONLY`（`/argus setback <玩家> cancel`），修复 `/argus export` 导出记录条数统计（`LogQueryService.ExportResult`）与 `/argus status` 历史归档文件数统计（`AuditLogManager#getArchiveFileCount`）。

## [1.2.2] - 2026-10-31

### 新增
- **基于 Modrinth API v2 的自动更新子系统 (`ModrinthAutoUpdater`)**：
  - **跨平台多内核独立异步调度**：采用纯 Java 原生 `ScheduledExecutorService` 守护线程池驱动周期性轮询与异步检测，彻底剥离外部未定义调度器依赖，实现跨 Java 8 ~ 26.3+ 及 Spigot / Paper / Purpur / Folia 零异常稳定运行。
  - **双轨反射安全版本识别**：优先接入 Paper 1.20.4+ 的 `PluginMeta` 现代契约，平滑向下回退至 Bukkit `getDescription()`，彻底消除 `getDescription()` 废弃编译警告。
  - **多版本双轨 Gson 降级解析机制**：无缝适配 1.12.2 旧版 Gson 与 2.8.6+ 现代 Gson，杜绝在老旧服务端核心上触发 `NoSuchMethodError`。
  - **SHA-512 流式完整性校验与原子级落盘**：内置 `DigestInputStream` 边下边验哈希防篡改保护，配合 `.part` 临时流与 `ATOMIC_MOVE` 原生原子重命名，确保落盘至 `plugins/update/` 的更新包完整可用并在下次服务端冷启动时由 Bukkit 原生替换生效。
  - **全链路运维指令与 i18n 多语言联动**：无缝打通 ArgusAC 多语言体系，提供 `/argus update [check|download]` 管理员运维指令及 Tab 自动补全，支持启动自检、周期性轮询与发布渠道（release/beta）筛选。

### 修复
- **受箭矢远程射击击退时的 Reach 与 KillAura 误报彻底根除**：
  - **动态受击击退免检窗口优化**：重构 `ReachCheck` 中受击短周期保护时序，将放行窗口扩展为 `Math.max(450ms, ping + 350ms)`，全面联动 `hasRecentVelocity(1500ms)` 与 `hasAnyLegalVelocity()`，完整覆盖弓箭强击退的物理抛物线加速周期与网络 RTT 确认延迟。
  - **攻击者受击历史位置回溯**：在 `PlayerData` 中受击瞬间记录受击前原始坐标（`preDamageLocation`），并在 1500ms 窗口内动态注入 `getCandidateEyeLocations` 候选眼部位置集合，消除服务端权威击退位移导致自身坐标单向拉伸脱节造成的超距假象。
  - **平滑击退动态容差与分级缓冲**：在受击击退活跃期引入随时间平滑衰减的自适应击退距离补偿（`+0.85 ~ 2.05` 格），并将低确信缓冲提升至 `0.85` 格，严格限制击退期的违规升级步进，杜绝玩家受击正常还击被无故取消。
  - **KillAura 击退视角扭转与镜头抖动容差**：在处于合法击退时将最大攻击夹角容限提升 `45.0` 度、盲区阈值放宽至 `135.0` 度，并将连续异常确认阈值提高至 4 次，彻底消除受击镜头震颤（Screen Shake）与急转还击导致的虚假 KillAura 拦截。
  - **严格近战 DamageCause 白名单过滤**：在 `PlayerEventListener`、`ReachCheck`、`KillAuraCheck` 与 `MaceCheck` 入口处严格限定 `DamageCause.ENTITY_ATTACK` 及直接攻击者校验，彻底隔离荆棘反伤（`THORNS`）、远程投掷物（`PROJECTILE`）与药水伤害穿透进入近战检测。
  - **远程射手实体历史追踪补录**：在箭矢命中玩家时通过 `Projectile#getShooter` 将真正的射手生物自动补录进 `EntityHistoryTracker` 延迟回溯快照，确保受击玩家远距离反击射手时能够正常匹配历史碰撞箱。
- **带梯子方块边缘潜行移动误报（`Fly`、`NoFall`、`Setback`）**：
  - **AABB 与水平相邻攀爬物全向扫描**：引入 `VersionAdapter#isClimbableAround`，覆盖潜行探出（`0.45` 格）8 探针与四向水平相邻方块（东、南、西、北）的挂载梯子与藤蔓采样，纵向涵盖脚下至头部（`-1.0 ~ 1.5` 格）。
  - **梯子潜行边缘免检判定**：在 `PlayerEventListener` 与 `FlyCheck` 中全面联动攀爬物环境，在玩家沿带梯子方块边缘挪动时及时重置 `airTicks` 与悬停计时器，彻底根除边缘潜行时的 `Fly (Hover)` 误判。
  - **NoFall 特殊环境联防机制**：将攀爬物临近状态纳入 `specialEnv` 与接地状态机，杜绝客户端在梯子上悬停发包 `onGround=true` 时被误判为 `Packet GroundSpoof`。
  - **回退重力下坠压制隔离**：在 `SetbackManager#directCancel` 中将攀爬环境从真空重力加速度模拟中完全排除，彻底解决梯子边缘被强制向下传送拉扯陷入死循环回退的问题。
- **Minecraft 1.12.2 到最新版本（1.20.x / 1.21.x / 26.x）全链条跨版本兼容性深度重构**：
  - **1.12.2 类加载 NoSuchFieldError 隐患根除**：
    - 移除了 `ElytraFlyCheck` 对高版本材质 `Material.FIREWORK_ROCKET` 与 `Material.BUBBLE_COLUMN` 的直接静态常量引用，重构为具备名称双向回退的兼容判定（`FIREWORK_ROCKET` / `FIREWORK`，`BUBBLE_COLUMN` / `SOUL_SAND`）。
    - 移除了 `AlertManager` 对 `Sound.BLOCK_NOTE_BLOCK_BASS` 常量的静态编译硬依赖，重构为通过 `VersionAdapter#playSound` 动态调度，并在 `LegacyVersionAdapter` 中自动映射低版本音效名（如 `BLOCK_NOTE_BASS`）。
    - 移除了 `PlayerEventListener` 对 `Material.ELYTRA` 的字面量硬绑定，提升各平台极端材质别名映射的容错率。
  - **1.12.2 实体 AABB 反射与多生物碰撞箱精准适配**：
    - 修复了 `LegacyVersionAdapter` 错误缓存 `CraftPlayer#getHandle` 导致非玩家生物（僵尸、骷髅等）调用抛出 `IllegalArgumentException` 并误将全局 `aabbReflectionFailed` 置为 true 的严重设计缺陷；将其重构为对实体基类 `CraftEntity` 的安全反射，保证攻击怪物与 NPC 时 AABB 采样 100% 正常。
    - 在 AABB 数学兜底层接入 Bukkit 1.11+ 的 `entity.getWidth()` 与 `entity.getHeight()`，解决所有实体被一律按 0.6x1.8 盒模型估算导致的判定偏差。
  - **物品耐久接口抽象与 1.12.2 接口缺失隔离**：
    - 在 `VersionAdapter` 引入统一的 `isItemBroken(ItemStack)` 接口；在 `LegacyVersionAdapter` 中使用原版 `getDurability()`，在 `ModernVersionAdapter` 中使用 1.13+ 的 `org.bukkit.inventory.meta.Damageable`，彻底消除 1.12.2 上引用 `Damageable` 带来的 `NoClassDefFoundError` 隐患。
  - **1.21.2+ / 1.21.4 / 26.x 属性系统剥离 GENERIC_ 前缀适配**：
    - 重构了 `ModernVersionAdapter` 与 `LegacyVersionAdapter` 中的属性解析机制（`resolveAttribute` 与 `getAttributeValue`），动态适配 1.21.2+ Mojang 将 `GENERIC_` 前缀剥离（如 `MOVEMENT_SPEED` 替代 `GENERIC_MOVEMENT_SPEED`）的变更，自动优先匹配新版属性名并平滑回退，杜绝新版本核心下的枚举解析异常。
  - **Adventure / Spigot 纯净旧版本降级保护**：
    - 优化 `ColorUtil` 与 `LegacyVersionAdapter#sendMessage` / `sendActionBar`：增加运行时 `HAS_ADVENTURE` 类存在性探针，在无 Adventure 库的 1.12.2 Spigot 环境下安全直连原版 `ChatColor.translateAlternateColorCodes` 与 Bungee ActionBar API，避免频繁捕获 `NoSuchMethodError`。
  - **预测器附魔读取规范化**：
    - 将 `DeterministicMovePredictor` 中对深海步行的原始枚举调用统一收敛至 `VersionAdapter#getDepthStriderLevel`，实现附魔跨版本平滑适配。

## [1.2.1] - 2026-10-30

### 新增
- **Netty 数据包级战斗拦截**：在 Netty 协议层直接拦截攻击封包，实现零延迟静默丢包。
- **下游广播静默消除**：对被拦截的非法攻击，彻底屏蔽其实体状态、受击动画与音效的广播同步。
- **分级处置策略**：对低确信度违规采用静默丢包，对严重违规执行严格位置回退。
- **重锤漏洞防御**：严防伪造下落数据包欺骗（Fake-fall Packet Spoofing）及平地重锤重击漏洞。

### 修复
- **线程安全与数据包流水线修复**：移除了 `PacketPipelineManager` 中非主线程的 `Entity#setVelocity` 与异步 `World#getBlock` 调用；将 `NoFall` 虚假落地检测与主线程环境状态强同步，杜绝落在平台边缘时的误判。
- **回退模式路由与事件取消一致性**：修复 `SetbackManager#mitigate` 与 `ViolationManager` 将 `SOFT`/`HARD` 模式及 `SetbackOverride` 错误短路为 `DIRECT_CANCEL` 的问题；移除 `SpeedCheck` 和 `JesusCheck` 中绕过 `min-vl` 阈值的原生 `event.setCancelled(true)` 调用。
- **战斗抑制与预测器集成**：修复 `CombatMitigationHandler#suppressAttack` 中的时间戳过期逻辑；将 `DeterministicMovePredictor` 接入活动移动流水线；在 `CompatibilityManager` 中完整注册全部 16 项检测。
- **FastEat 与 FastBow 误报修复**：防止连续右键交互及副手检测重置 `eatStartTick` 与 `bowChargeStartTick`；对同 tick 内的多重箭（Multishot）弩箭副箭实施豁免，消除加特林连射（Gatling Rapid Fire）误报。
- **移动类误报（`Fly`、`Speed`、`Jesus`、`ElytraFly`、`Timer`）**：
  - 动态关联 `Jump Boost`（跳跃提升）等级缩放 `FlyCheck` 滞空 tick 与上升顶点阈值；对粘液块与床的弹跳豁免 `IllegalAscent`；为栅栏、墙体和竹子追加 1.5 格碰撞支持。
  - 在 `SpeedCheck` 中接入自定义 `walkSpeed` 与 `GENERIC_MOVEMENT_SPEED` 属性，并支持 8-tick 的灵魂疾行（Soul Speed）动量保留。
  - 在 `JesusCheck` 中扩充对附近船只（Boat）、竹筏（Raft）与潜影贝（Shulker）实体碰撞箱的判定支持。
  - 为 `TimerTracker` 和 `ElytraData` 增加受控的网络延迟追赶额度（`MAX_CATCHUP_BALANCE`），消除网络卡顿冻结（`>1000ms`）恢复后的虚假 `Timer` 告警；放宽大角度俯冲重力坠落下的 `ElytraSpeed` 判定。
- **战斗与世界交互误报（`Velocity`、`Mace`、`KillAura`、`FastBreak`、`Scaffold`）**：
  - 为 `VelocityCheck` 增加水平墙体阻挡判定与击退峰值响应追踪。
  - 在 `MaceCheck` 中保留落地瞬间前的高空坠落下落顶点状态（`150ms` 缓冲窗口），根除压哨完美落地点命中时的虚假地面重击（Ground smash）误报。
  - 将 `KillAuraCheck` 视线遮挡光线追踪扩展为 3 点采样（眼部、中心、足底），杜绝穿过半砖/阶梯缝隙时的误报。
  - 修复 `FastBreakCheck`，将其耗时基准调整为自 `diggingStartTime`（`START_DESTROY_BLOCK` / `BlockDamageEvent`）起算，而非上一方块的破坏时间戳；放宽方块边缘搭桥时 `ScaffoldCheck` 的俯仰角（Pitch）限制。
- **潜行边缘防掉落豁免与悬崖回退吸附**：引入 8 方向 0.42 格边缘判定以完全适配原版潜行防掉落机制，彻底消除在方块边缘边缘试探时的虚假 `Fly (Hover)` 误报；重构悬崖回退吸附逻辑，将玩家向后拉回至稳固平台（`Y = -55.00`），避免直接将玩家推入虚空。
- **缓慢效果地面与滞空动力学校准**：全面适配高等级缓慢效果（例如缓慢 IV -60%）下的疾跑跳跃动力学（起跳 0.40 格、滞空 0.33~0.36 格、地面底线 0.20 格），彻底解决喷溅型缓慢药水下的 Speed 误报。
- **回退穿模下陷死锁修复**：彻底修复 `directCancel` 将地面玩家误判为滞空并强制附加 `-0.08` 重力下坠、将玩家封锁在地下（`-60.00` -> `-60.08` -> `-60.16`）的严重缺陷，回退现在严格遵循实体表面 Y 轴底线。
- **瞬发硬拦截绝对位移约束**：在执行瞬发硬截断前强制校验最低绝对位移阈值（`distance >= 0.38` / `0.42`），防止在低速移动时因相对倍率误判首步违规。
- **战斗判定精度与受击反击防误判优化**：修复近战角度计算偏差与陈旧角度盲区导致的误判；扩展 `EntityHistoryTracker` 回溯窗口以完整覆盖客户端 3-tick（`150ms`）实体插值延迟，在 `ReachCheck` 与 `KillAuraCheck` 中引入攻击者多候选眼部位置比对、受击击退宽限与转身反击连续性确认，在 `DeterministicMovePredictor` 受伤/击退豁免期实时同步状态机坐标消除豁免结束首帧残差突增，移除战斗违规借用 `Speed [HARD]` 触发强制传送回退的错误耦合，并严格区分“移动取消”、“位置回退”、“攻击取消”与“方块交互取消”的玩家及管理员端提示。
- **Reach 检测修复**：过滤了对自己受伤时的检测，防止了误报。
- **协议栈底层交互与连点器工业级重构 (Combat Protocol Overhaul)**：
  - **Attack 与 Swing 乱序双向配对**：支持客户端模组端 `Attack` 先于 `Swing` 到达或偶发丢包，允许同一 Tick 或相邻 Client Tick 内双向自动配对，设立连续无挥臂门槛，消除机械的前置挥臂硬校验误报。
  - **挖掘状态与战斗交互硬隔离 (Dig-Attacking Transition Wall)**：建立挖掘态硬截断机制，玩家打碎方块或松开左键瞬间的首次战斗攻击不计入间隔采样，杜绝 20 CPS 挖掘样本污染连点队列。
  - **Flying 虚拟时钟与 TCP Burst 攒包平滑**：以客户端 `Flying` 移动包作为时间递进虚拟刻度，结合漏桶与样本离散度/标准差矩阵，彻底根除物理系统时钟在网络抖动和 TCP 批量 Flush 时的瞬时假高频。
  - **1.8 防砍 (Block-Hit) 双重发包解耦**：将右键格挡/使用流与左键攻击流物理隔离，容许防御期间攻击穿透，保证连点状态机不被打断。
  - **右键实体孪生包 (InteractAt & Interact) 去重**：对同一 Client Tick 内针对同一实体的 `INTERACT_AT` 与 `INTERACT` 自动聚合归一化，防止单次右键产生翻倍交互计次。
  - **快捷栏切换 (Slot Switch) 延迟回溯缓冲**：实时监听 `HELD_ITEM_CHANGE` 并维护槽位切换时间戳，在换刀瞬间提供攻击距离与属性宽容，防止网络 RTT 导致的 Reach 误报。
  - **切屏失焦与界面关闭防按键粘滞 (Focus Recovery Grace Period)**：监听 `CLOSE_WINDOW` 与长间隔挂起恢复，在窗口重新获得焦点前 350ms 内提供微平滑缓冲，吸收 GLFW 按键粘滞补包。

### 变更
- **战斗底层架构重构**：重构成基于数据包流的中心化战斗处理引擎，Bukkit 伤害事件降级为后备兜底。

## [1.2.0] - 2026-10-23

### 新增
- **负数 Teleport ID 二进制隔离**：强制所有回退传送使用 `random.nextInt() | Integer.MIN_VALUE`，在防作弊回退（负数）与原版/插件传送（非负数）之间建立彻底的位运算隔离。
- **防忽略传送逃逸再同步**：对标 GrimAC 的 `checkTeleportQueue` 与 `BadPacketsN`，跟踪空中未确认的 Flying 数据包，强制重新同步不配合的外挂客户端。
- **数据包级坐标硬夹持 (Hard-Clamping)**：在 PacketEvents 流水线层直接重写偏离中的飞行移动包内部 X/Y/Z 坐标，防止坐标泄露。
- **载具弹出与脱离同步**：回退时强制玩家离开载具（`player.leaveVehicle()`）、清除载具速度并同步坐骑坐标，杜绝 BoatFly 与马匹去同步漏洞。
- **回退期间动作抑制 (`SetbackBlocker`)**：在飞行状态和违规锁定期间，在协议层全面拦截 `INTERACT_ENTITY`、`ATTACK` 和 `PLAYER_DIGGING` 数据包。
- **严格回退锚点不变量**：连续违规期间强制锁定 `lockedAnchorLocation`，将回退目标严格固定在作弊前的基准点。
- **数据包级零速度重置**：回退时下发 `WrapperPlayServerEntityVelocity(0, 0, 0)`，抹杀客户端按键带来的惯性动量。
- **FastEat 与秒吃防御**：服务端食用 Tick 计数器（32 ticks / 1600ms 基准及 4-tick 容差）、数据包欺骗检测、Ping 自适应 RTT 延迟补偿与主副手物品同步。
- **FastBow 与加特林弓防御**：服务端蓄力 Tick 计数器（20 ticks / 1000ms 基准及 2-tick 容差）、滑动窗口突发保护、射击频率限制与可配置的箭矢伤害削弱。
- **双层战斗拦截机制**：打通 Bukkit 事件与 PacketEvents 流水线，实现对攻击与伤害的统一拦截。
- **鞘翅与 PacketFly 防御**：动能守恒验证（$E=mgh+\frac{1}{2}mv^2$）、胸甲耐久扣除及水平巡航压制。
- **冰面滞空动量保留 (`airborneFromIce`)**：追踪起跳来源，在整个空中抛物线中保留合法的冰面跳跃动量。
- **解耦配置系统**：将所有 15 个检测项配置独立拆分至 `checks.yml` 文件中，支持 `/argus reload` 热重载。

### 变更
- **对标 GrimAC 级别的 Anti-Creep 与防去同步回退重构**：升级回退机制以对齐 GrimAC 的去同步引擎，消除蠕变（Creeping）去同步漏洞。
- **瞬发硬拦截与零延迟移动缓解**：针对严重超速突发（`diff >= 0.18 || distance > limit * 1.50`）标定瞬发硬截断，并将未受限的速度缓冲上限从 0.40 收紧至 0.12。
- **锁定状态下的超速违规缓冲**：在 2000ms 违规锁定期间动态将 `speedBufferMax` 降至 0.04，消除作弊蠕变窗口。
- **SpeedCheck 下阶梯与下坡动量容差**：将下坡动量补偿（`fallDelta >= 0.12`）提升至 1.30x（最高 0.4225 上限），以完美兼容原版阶梯/半砖疾跑机制。
- **地面疾跑与连跳过渡容差**：将地面疾跑基准提升至 0.325，并在着陆前 1~2 tick（`groundTicks <= 2`）提供 0.39 格的过渡冲量。
- **空中动态击退与速度豁免**：将通用击退/受击豁免窗口从 500ms 延长至 1000ms，并在滞空期间（`!isOnGround || airTicks > 0`）自动顺延。
- **Fly 与悬停检测拦截**：将起跳上升包络调整至 1.05（+ 药水加成），水平悬停检测阈值收紧至 2 ticks，并将 `max-air-ticks` 从 25 降低至 12。
- **即时 Jesus 拦截**：在水面伪造接地时强制执行即时事件取消，并将平飞悬停和滞留阈值降低至 2 和 5 ticks。
- **Timer 漂移处置**：当 Timer 漂移突破 -70ms 亏空限制时直接丢弃入站 Flying 数据包，并将网络抖动容忍上限 `DEFICIT_LIMIT` 校准至 -150.0ms。
- **快照防投毒保护**：阻止在超速缓冲可疑期间（`speedBuffer > 0.04`）的位置记录存入合法地面快照历史。
- **Bukkit 飞行状态取消强化**：在未与目标坐标对齐的在途飞行状态下，显式取消 `PlayerMoveEvent`。
- **回退引擎模块化**：将单体 `SetbackManager` 解耦为包 `com.example.argusac.setback` 下的各个独立子组件（`SafeLocationFinder`、`SetbackPhysics`、`SetbackPacketSync`、`SetbackNotifier`、`PlayerSetbackContext`）。
- **Minecraft 26.3 版本对齐**：在 `ServerVersion`、`plugin.yml`、`README.md` 与 `.gitignore` 中规范化版本解析、枚举名称（`V26_0`、`V26_3`）及友好显示名称。

### 移除
- **SpeedCheck 中的向前移动辅助**：彻底移除 `SpeedCheck` 中用于向前推导向量的 `clampedTo` 计算；超速违规将触发严格的就地取消。
- **侵入式战斗抑制**：移除武器冷却重置与挥剑蓄力清除，恢复纯净的原版 PvP 机制。
- **人为回退冷却**：彻底废除物理移动回退上人为设置的 350ms 冷却时间，无免疫窗口拦截每一个违规 Tick。

### 修复
- **跳跃波峰、起跳与上阶跳误报 (`FlyCheck`)**：通过将 `maxAllowedLaunchAscent` 提高至 1.05 修复跳跃上阶误判，并扩展抛物线衰减以覆盖第 5~6 tick（$v_Y \approx 0.003$ 波峰）。
- **地面探测深度校准**：将 `hasBlockBelow` 探测深度恢复为自 0.0 偏移起算 0.60 格，正确识别半砖/台阶表面。
- **主动悬停抑制引擎地面回退 (`SetbackManager` & `ActiveHoverTracker`)**：将单点检测替换为 5 点 AABB 探测，并加入 `data.isOnGround()` / `airTicks <= 0` 放行门控，解决地面疾跑时突发的 `[HARD]` 误回退。
- **Timer 抖动缓冲与回退再同步**：在所有回退出口（`directCancel`、`executeHardSetbackOnMainThread` 与 `SetbackNotifier`）中追加 `timerTracker.reset()`，消除级联式的 `BadPackets (Timer)` 虚假告警。
- **SpeedCheck 下阶梯虚假回退**：通过扩充下坡势能容限，消除在台阶和半砖上产生 0.16 格垂直落差时的虚假速度回退。
- **空中击退过早过期**：修复长距离空中抛物线轨迹中，因 500ms 击退计时过期导致的空中速度虚假回退。
- **冰面跳跃空中动量丢失**：修复脱离冰面 4-tick 地面记忆过期后，长距离跳跃过程中突发的 Speed 告警。
- **预装填弩虚假告警 (`FastBowCheck`)**：对已预装填的弩（Crossbow）豁免拉弦蓄力 Tick 要求。
- **创造模式快速破坏方块误报 (`AutoClickerCheck`)**：针对创造与旁观模式下快速搭路和破坏方块的行为添加显式豁免。
- **NoFall 可站立表面防护 (`PacketPipelineManager`)**：强化地面欺骗检测，加入可站立表面扫描（地毯、半砖、楼梯、活板门、雪层、脚手架）。
- **倒置的击退响应窗口 (`VelocityCheck`)**：修正 Ping 自适应 RTT 响应窗口逻辑，消除受到击退初始移动时的误判。
- **波峰与特殊方块误报 (`FlyCheck`)**：明确区分波峰过渡态与水平悬停，豁免蜘蛛网、细雪和甜浆果丛。
- **战斗网络延迟误报 (`Reach` & `KillAura`)**：增加非玩家实体的延迟补偿，并为高 DPI 玩家提供甩头攻击（Flick-shot）角度容限。
- **水上跳跃、游泳与下潜回退死循环根除 (`FlyCheck` & `SetbackManager`)**：彻底重构流体环境判定，移除原先将“头部浮出水面游泳”和“水面海豚跳”误判为真空飞行的双重浸入水下前置条件。液体环境现在统一重置 `airTicks`，流体异常严格交由 `JesusCheck` 裁决。此外，回退时彻底豁免液体环境下的真空重力加速度模拟，彻底解决以往将水面跳跃玩家拉扯回海底的恶性拉回循环。
- **疾跑跳与连续 Bunnyhop 速度误报 (`SpeedCheck`)**：
  - 修正起跳帧数据包到达乱序：在跳跃上升阶段（`ΔY > 0.03`），立即绑定起跳速度包络并给予宽容的 0.44 格上限，充分吸收原版向前起跳冲量（`0.36~0.38` 格/tick）；
  - 将地面疾跑基准从 0.325 提升至 0.340（覆盖 45 度疾跑侧滑），并将落地过渡容限提升至 0.40 格；
  - 将无约束的 `speed-buffer-max` 从 0.12 放宽至工业标准的 0.25（衰减率提升至 0.10），以容纳连续 Bunnyhop 的方差波动，同时保留瞬发硬截断（`diff >= 0.18 || distance > limit * 1.50`）以及违规锁定下的严格 0.04 缓冲上限。
- **锚点过早释放修复**：修复在 400ms 内没有新拦截时锁定锚点即被提前释放的缺陷，严格执行完整的 2000ms 锚点锁定时长。

## [1.1.5] - 2026-10-17

### 新增
- **载具状态同步**：执行回退时使载具与玩家保持同步传送。

### 变更
- **回退系统现代化**：在取消移动事件时精准模拟原版重力加速度。
- **细分方块贴地校准**：完善对台阶、积雪和地毯的吸附对齐，避免回退后的微小下落。

## [1.1.4] - 2026-10-17

### 修复
- **即时事件取消**：废除瞬时移动事件取消机制的免疫窗口。
- **空中悬停对抗**：新增主动重力任务（Active Gravity Task），对抗利用暂停发包实现的滞空作弊。
- **冰面超速防御**：限制滑动速度上限，削减脱离冰面后的动量残留记忆。

## [1.1.3] - 2026-10-17

### 新增
- **JesusCheck 检测模块**：防御水上伪造接地、水面悬停及水上行走（WaterWalk）漏洞。

### 变更
- **流体动力学优化**：优化上浮游泳判定容差与连续跳跃冲量。

### 修复
- **Fly 误报修复**：修复疾跑跳跃达到波峰阶段时的非法拔高误判。

## [1.1.2] - 2026-10-17

### 修复
- **移动逻辑修复**：收紧接地检测容差，优化碰撞箱自适应。
- **零 Tick 回退**：修复 HARD 模式下移动事件的目标位置去同步漏洞。
- **飞行拦截**：收紧滞空时间阈值，引入 FastFly 极速飞行拦截。

## [1.1.1] - 2026-10-17

### 修复
- **KillAura 防护**：加入攻击冷却重置与瞬转角（Snap Angle）检测。
- **Speed 误报修复**：升级碰撞箱采样精度，加入冰面动量追踪。
- **连点器优化**：统一收口 CPS 采样至原生挥臂动画数据包。

## [1.1.0] - 2026-10-17

### 新增
- **内置集成 PacketEvents 2.7.0**：通过 Shaded Relocation 彻底移除对 ProtocolLib 的外部依赖，开箱即用支持 Spigot/Paper 1.12.2 至 1.21.x。
- **确定性移动模拟预测引擎**：全包络物理运动学仿真系统，基于客户端输入计算理论残差。
- **延迟补偿实体历史回溯**：维护 1000ms 实体 AABB 历史快照，彻底消灭网络延迟造成的超距（Reach）误报。
- **协议层 Timer 检测**：纳秒级时钟漂移平衡引擎，精准侦测 Timer 加速与数据包轰炸。
- **战斗数据包流水线**：原生数据包监听实体交互与挥臂事件。
- **跨版本安全视线射线判定**：在 `VersionAdapter` 中加入多态视线遮挡射线追踪。

## [1.0.0] - 2026-10-17

### 新增
- **模块化防作弊检测项**：涵盖移动、战斗及方块交互的核心检测矩阵。
- **ElytraFly 鞘翅防护套件**：针对鞘翅速度、悬停与飞行漏洞提供全面防护。
- **回退与告警机制**：违规管理、管理员告警及灵活的回退配置方案。
- **审计与工具**：异步日志记录、哈希链校验以及回放辅助工具。
