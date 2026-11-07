# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Language / 语言切换**:  
> [English Version](#english-changelog) | [中文版本 (Chinese Version)](#更新日志)

---

<a id="english-changelog"></a>
## [1.2.3] - 2026-11-07

### Fixed
- **FastEat False Positives (Offhand Consumption & TPS Fluctuations)**:
  - Fixed a bug in `PlayerInteractListener#onPlayerInteract` where holding an uncharged bow/crossbow in the main hand while eating food from the offhand skipped the offhand consumption start timestamp, causing 100% `Instant Consume` false flags upon completion.
  - Added protocol-level `USE_ITEM` consumption tracking and `RELEASE_USE_ITEM` digging action reset (`data.stopEating()` & `data.stopBowCharge()`) in `PacketPipelineManager`.
  - Upgraded `FastEatCheck` from a single-sided `||` check to a combined tick-and-millisecond validation (`FastEatCheck#isFastEatViolation`) with a `< 75%` extreme short-duration hard threshold, and extended `PlayerData#startEating` re-interact protection window from `2000ms` to `3500ms` to prevent false flags during moderate server TPS drops (16~18 TPS).
- **ElytraFly (`ElytraFirework`) Post-Dive Pull-Up & Momentum Glide False Positives**:
  - Added takeoff grace period exemption (`!inTakeoffGrace`), vertical downward momentum conversion exemption (`deltaY >= -0.05 && lastDeltaY >= -0.05`), and post-dive natural deceleration glide recognition (`ElytraFlyCheck#isDecayingPostDiveGlide`) so high-speed glides after steep dives are no longer misflagged as unpropelled firework boosts.
- **Velocity (`Anti-KB`) Network Jitter & Damping Block False Positives**:
  - Introduced a `250ms` jitter observation window (`maxWaitTime = Math.min(1000L, minWaitTime + 250L)`) and a `1500ms` stale velocity expiration guard in `VelocityCheck`.
  - Added exemptions for cobwebs (`WEB`), sweet berry bushes (`BERRY_BUSH`), powder snow (`POWDER_SNOW`), honey blocks, soul sand, slime blocks, bubble columns, water, and adjacent climbables.
- **FastBreak Mining Fatigue & Continuous Digging False Positives**:
  - Added dynamic threshold calculation (`FastBreakCheck#calculateMiningFatigueThresholdMs`) factoring in Mining Fatigue amplifier, tool Efficiency enchantment level, Haste effect, and `time-leniency`, resolving false positives when mining normal ores with Efficiency V picks under Mining Fatigue I.
  - Fixed `PlayerData#startDigging` overwriting the initial `diggingStartTime` during continuous block breaking.
- **Sneak-Bridging Edge Ground Detection & Fence/Wall Height Alignment**:
  - Upgraded `PlayerMoveListener#isGrounded` to a 9-point probe supporting a `0.42` sneak edge overhang radius, preventing `NoFall Packet GroundSpoof` false flags while sneak-bridging.
  - Added `1.5`-block collision height recognition for `FENCE`, `WALL`, and `BAMBOO` in `SafeLocationFinder#isGrounded`.
- **BadPackets Sneak-Sprint & Stationary Keep-Alive Packet Handling**:
  - Required actual horizontal speed (`horizDist > 0.20`) before flagging conflicting `Sneak-Sprint` states, and removed the unreachable duplicate move branch that could misflag vanilla stationary 20-tick position packets.

### Changed
- **Memory Leak Prevention & Redundancy Cleanup**:
  - Fixed a memory leak in `PlayerLifecycleListener#onPlayerQuit` where calling `getContext(uuid).clearLockedAnchor()` recreated an empty `PlayerSetbackContext` in `SetbackManager#contextMap` after quit; switched to `removeContext(uuid)` and introduced read-only `getExistingContext(uuid)` across `PacketPipelineManager`, `LogQueryService`, and `PlayerData`.
  - Consolidated `clearLockedAnchor()` into `PlayerSetbackContext#reset` and `clear()`, removing duplicate teleport/lifecycle listener calls and unused `PunishmentManager#executeDefaultPunishment`.
  - Added `SetbackOverride.CANCEL_ONLY` (`/argus setback <player> cancel`), accurate exported entry counts (`LogQueryService.ExportResult`), and real `.jsonl.gz` archive file counting (`AuditLogManager#getArchiveFileCount`) in `/argus status`.

## [1.2.2] - 2026-10-31

### Added
- **Modrinth API v2 Auto-Updater Subsystem (`ModrinthAutoUpdater`)**:
  - **Cross-Platform Asynchronous Engine**: Driven by a pure Java standard `ScheduledExecutorService` daemon pool for periodic polling and background checks, removing external scheduler dependencies and ensuring zero-exception execution across Java 8 ~ 26.3+ and Spigot / Paper / Purpur / Folia.
  - **Dual-Track Reflection Plugin Version Resolver**: Prioritizes Paper 1.20.4+ `PluginMeta` APIs with seamless fallback to legacy Bukkit `getDescription()`, completely eliminating deprecated compiler warnings.
  - **Dual-Track Gson Parsing Compatibility**: Seamlessly adapts between legacy 1.12.2 Gson and modern Gson 2.8.6+, preventing runtime `NoSuchMethodError` across various server distributions.
  - **Streaming SHA-512 Verification & Atomic File Move**: Employs `DigestInputStream` on-the-fly checksum verification combined with `.part` staging files and `StandardCopyOption.ATOMIC_MOVE` to guarantee corruption-free downloads in `plugins/update/` for Bukkit's native restart replacement.
  - **Full Operational Integration & i18n Localization**: Deeply coupled with the ArgusAC internationalization architecture, introducing `/argus update [check|download]` admin commands with smart tab completion, configurable startup checks, polling intervals, and channel filtering (release/beta).

### Fixed
- **Under-Attack Reach & KillAura False Positives Under Projectile Knockback**:
  - **Dynamic Combat Knockback Exemption Window**: Expanded the short-cycle under-attack exemption window in `ReachCheck` to `Math.max(450ms, ping + 350ms)` and integrated `hasRecentVelocity(1500ms)` along with `hasAnyLegalVelocity()`, fully absorbing the physics impulse delay and client RTT of remote arrow hits.
  - **Attacker Knockback Backtracking**: Recorded pre-damage world coordinates in `PlayerData` upon taking external damage/knockback and injected them into `getCandidateEyeLocations` within a 1500ms window, resolving eye-to-target distance inflation caused by server authoritative knockback pushes.
  - **Adaptive Distance Decay & Low-Confidence Buffering**: Implemented smooth time-based knockback distance compensation (`+0.85 ~ 2.05` blocks) and widened the low-confidence buffer to `0.85` blocks during combat knockback, preventing legitimate counter-attacks from being misflagged or cancelled.
  - **KillAura Knockback Angle Tolerance**: Relaxed the under-knockback angle tolerance by `45.0` degrees and widened blindspot limits to `135.0` degrees; elevated the anomaly streak confirmation threshold to 4 consecutive hits under active knockback to eradicate false `KillAura` flags during damage screen tilts.
  - **Strict Melee DamageCause Filtering**: Enforced strict `DamageCause.ENTITY_ATTACK` and direct damager equality checks in `PlayerEventListener`, `ReachCheck`, `KillAuraCheck`, and `MaceCheck`, completely isolating `THORNS` enchantment recoil, projectiles, and magic damage from melee combat pipelines.
  - **Remote Shooter Entity Tracking**: Ensured true shooter entities (`Projectile#getShooter`) are registered into `EntityHistoryTracker` upon arrow impacts to maintain valid lag-compensation snapshots when victims retaliate against archers.
- **Ladder & Climbable Sneak Movement False Positives (`Fly`, `NoFall`, `Setback`)**:
  - **Expanded Climbable AABB & Adjacent Scan**: Introduced `VersionAdapter#isClimbableAround` covering an 8-direction probe (`0.45` blocks) and 4-way horizontal neighbor scanning for mounted ladders and vines across foot to head vertical levels (`-1.0 ~ 1.5` blocks).
  - **Sneak Edge Climbable Exemption**: Integrated climbable surroundings into `PlayerEventListener` and `FlyCheck`, actively resetting `airTicks` and hover timers when players sneak along block edges with attached ladders.
  - **NoFall Special Environment Protection**: Included climbable proximity in `specialEnv` and ground-state maintenance, preventing false `Packet GroundSpoof` flags when clients report `onGround=true` while suspended on ladders.
  - **Setback Downward Gravity Isolation**: Exempted climbable environments from vacuum gravity simulation in `SetbackManager#directCancel`, eliminating the downward force-pull that previously dragged sneaking players off ladder edges into a setback loop.
- **End-to-End Cross-Version Compatibility Overhaul (1.12.2 to Latest 1.20.x / 1.21.x / 26.x)**:
  - **Eradication of 1.12.2 Classloading NoSuchFieldError Risks**:
    - Removed direct static constant references to `Material.FIREWORK_ROCKET` and `Material.BUBBLE_COLUMN` in `ElytraFlyCheck`, refactoring them to use dual-string backwards-compatible matching (`FIREWORK_ROCKET` / `FIREWORK`, `BUBBLE_COLUMN` / `SOUL_SAND`).
    - Removed hard compile-time dependency on `Sound.BLOCK_NOTE_BLOCK_BASS` in `AlertManager`, refactoring sound playback through `VersionAdapter#playSound` with automatic legacy mapping (`BLOCK_NOTE_BASS`).
    - Decoupled `PlayerEventListener` from direct literal bindings on `Material.ELYTRA`.
  - **1.12.2 Entity AABB Reflection & Multi-Entity Bounding Box Precision**:
    - Fixed a critical flaw in `LegacyVersionAdapter` where caching `CraftPlayer#getHandle` caused `IllegalArgumentException` on non-player entities (zombies, skeletons, NPCs) and permanently flagged `aabbReflectionFailed = true`. Refactored to safely reflect on base class `CraftEntity`, ensuring reliable AABB retrieval across all mobs.
    - Integrated Bukkit 1.11+ `entity.getWidth()` and `entity.getHeight()` into the mathematical fallback to replace rigid 0.6x1.8 bounding box approximations.
  - **Item Durability Abstraction & 1.12.2 Damageable Isolation**:
    - Introduced `VersionAdapter#isItemBroken(ItemStack)` to cleanly isolate item durability inspections. Uses native `getDurability()` on 1.12.2 and 1.13+ `Damageable` on modern versions, eradicating `NoClassDefFoundError` risks on legacy servers.
  - **1.21.2+ / 1.21.4 / 26.x Attribute System Adaptation (Stripped GENERIC_ Prefix)**:
    - Overhauled attribute resolution in `ModernVersionAdapter` and `LegacyVersionAdapter` (`resolveAttribute` and `getAttributeValue`) to dynamically support Mojang's removal of the `GENERIC_` prefix (e.g., `MOVEMENT_SPEED` replacing `GENERIC_MOVEMENT_SPEED`) in Paper 1.21.2+, with dual-name resolution and caching.
  - **Adventure / Pure Legacy Spigot Fallback Protection**:
    - Optimized `ColorUtil` and `LegacyVersionAdapter#sendMessage` / `sendActionBar` with a runtime `HAS_ADVENTURE` probe to gracefully fallback to native `ChatColor.translateAlternateColorCodes` and Bungee ActionBar API on plain 1.12.2 Spigot cores without Adventure.
  - **Predictor Enchantment Normalization**:
    - Normalized `DeterministicMovePredictor`'s Depth Strider enchantment lookup to use `VersionAdapter#getDepthStriderLevel`.

## [1.2.1] - 2026-10-30

### Added
- **Netty Packet-Level Combat Mitigation**: Intercepts attacks at the Netty protocol layer with zero-latency drops.
- **Downstream Broadcast Suppression**: Silences entity status, visual animations, and sound effects on blocked attacks.
- **Hierarchical Mitigation Strategy**: Employs silent drops for low-confidence flags and strict setbacks for severe violations.
- **Heavy Mace Exploit Defense**: Prevents fake-fall packet spoofing and flat-ground smash exploits.

### Fixed
- **Thread-Safety & Packet Pipeline Fixes**: Eliminated off-main-thread `Entity#setVelocity` and asynchronous `World#getBlock` calls in `PacketPipelineManager`; synchronized `NoFall` ground-spoof checks with main-thread environment states to prevent false flags when landing on platform edges.
- **Setback Mode Routing & Cancellation Consistency**: Fixed `SetbackManager#mitigate` and `ViolationManager` short-circuiting `SOFT`/`HARD` modes and `SetbackOverride` into `DIRECT_CANCEL`; removed raw `event.setCancelled(true)` calls in `SpeedCheck` and `JesusCheck` that bypassed `min-vl` thresholds.
- **Combat Suppression & Predictor Integration**: Fixed timestamp expiry semantics in `CombatMitigationHandler#suppressAttack`, wired `DeterministicMovePredictor` into the active movement pipeline, and registered all 16 checks in `CompatibilityManager`.
- **FastEat & FastBow False Positives**: Prevented repeated right-click interactions and off-hand checks from resetting `eatStartTick`/`bowChargeStartTick`; exempted `Multishot` crossbow secondary arrows within the same tick from `Gatling Rapid Fire` flags.
- **Movement False Positives (`Fly`, `Speed`, `Jesus`, `ElytraFly`, `Timer`)**:
  - Scaled `FlyCheck` air-tick and apex thresholds dynamically with `Jump Boost` amplifier, exempted slime/bed bounces from `IllegalAscent`, and added 1.5-block collision support for fences, walls, and bamboo.
  - Integrated custom `walkSpeed` and `GENERIC_MOVEMENT_SPEED` attributes and 8-tick `Soul Speed` momentum retention into `SpeedCheck`.
  - Added nearby `Boat`, `Raft`, and `Shulker` entity bounding-box support to `JesusCheck`.
  - Added controlled lag catch-up balance (`MAX_CATCHUP_BALANCE`) to `TimerTracker` and `ElytraData` to prevent false `Timer` flags after network freezes (`>1000ms`), and relaxed `ElytraSpeed` for steep gravity dives.
- **Combat & World False Positives (`Velocity`, `Mace`, `KillAura`, `FastBreak`, `Scaffold`)**:
  - Added horizontal wall-obstruction checks and peak response tracking to `VelocityCheck`.
  - Preserved recent high-fall apex states (`150ms` window) upon landing in `MaceCheck` to eliminate false `Ground smash` flags on frame-perfect landing hits.
  - Expanded `KillAuraCheck` wall raytracing to 3 points (eye, center, feet) to prevent false flags through slab/stair gaps.
  - Fixed `FastBreakCheck` to measure elapsed time from `diggingStartTime` (`START_DESTROY_BLOCK` / `BlockDamageEvent`) instead of the previous block's break timestamp, and relaxed `ScaffoldCheck` pitch limits when bridging on block edges.
- **Sneak Edge Prevention & Cliff Setback Snapping**: Added 8-direction 0.42-block edge detection for vanilla sneaking edge mechanics, eliminating false `Fly (Hover)` flags when teetering on block edges; overhauled cliff setback snapping to pull players back onto the solid platform (`Y = -55.00`) instead of pushing them into the void.
- **Slowness Ground & Airborne Dynamics**: Fully adapted to high-level Slowness (e.g. Slowness IV -60%) sprint-jump dynamics (0.40 launch, 0.33~0.36 airborne, 0.20 ground floor), completely eliminating Speed false positives under Splash Potion of Slowness.
- **Setback Ground Penetration Prevention**: Resolved critical bug where `directCancel` erroneously treated grounded players as airborne and forced a `-0.08` gravity drop, locking players underground (`-60.00` -> `-60.08` -> `-60.16`). Setbacks now strictly respect solid surface Y floors.
- **Instant Cutoff Scale Enforcement**: Enforced minimum absolute displacement thresholds (`distance >= 0.38` / `0.42`) before applying instant hard-cut mitigation, preventing relative multiplier false cuts on low-speed movements.
- **Combat Accuracy & Under-Attack Desync Mitigation**: Resolved angle calculation errors and stale-angle blindspot false flags; expanded `EntityHistoryTracker` to cover the client's 3-tick (`150ms`) entity interpolation window, evaluated candidate attacker eye positions (`packet`, `from`, `to`) with knockback tolerance and consecutive angle anomaly confirmation in `ReachCheck` and `KillAuraCheck`, synchronized `DeterministicMovePredictor` state coordinates during velocity/damage exemptions, removed false `Speed [HARD]` setbacks on combat violations, and separated notifications for movement cancellation, position setback, combat cancellation, and world interaction cancellation.
- **Reach Check Fix**: Filtered out detections when the player takes damage to prevent false positives.
- **Combat Protocol Overhaul & Advanced Boundary Hardening**:
  - **Attack & Swing Bi-directional Pairing**: Supported inverted packet ordering where `Attack` arrives before `Swing`, allowing bi-directional pairing across adjacent client ticks and eliminating rigid pre-swing requirements.
  - **Dig-Attacking State Transition Wall**: Implemented strict transition boundaries for mining states, discarding the initial post-break click interval to prevent 20 CPS digging samples from polluting combat queues.
  - **Flying Virtual Clock & TCP Burst Smoothing**: Driven click analysis by client `Flying` ticks combined with a leaky bucket and statistical deviation matrix, eliminating false spikes caused by TCP flush batches.
  - **1.8 Block-Hit Stream Decoupling**: Decoupled right-click block/item usage from left-click combat streams, allowing attack throughput without breaking the click state machine during sword blocking.
  - **InteractAt & Interact Twin-Packet Deduplication**: Unified duplicate right-click interactions targeting the same entity within the same client tick.
  - **Held Item Switch History Buffer**: Tracked `HELD_ITEM_CHANGE` timestamps and applied lag-compensated reach and attribute tolerances during rapid slot swaps.
  - **Window Focus Recovery & Key-Repeat Grace**: Handled `CLOSE_WINDOW` and long packet pauses with a 350ms grace window to absorb GLFW key-release drops and window focus recovery packet bursts.

### Changed
- **Combat Architecture**: Refactored to a centralized packet-level combat engine, demoting Bukkit events to fallbacks.

## [1.2.0] - 2026-10-23

### Added
- **Negative Teleport ID Binary Isolation**: Enforced `random.nextInt() | Integer.MIN_VALUE` for all setback teleports, creating complete bitwise isolation between anticheat setbacks (negative) and vanilla/plugin teleports (non-negative).
- **Anti-Ignore-Teleport Evasion Resynchronization**: Matches GrimAC `checkTeleportQueue` and `BadPacketsN` to track unacknowledged flying packets in-flight and forcefully resynchronize uncooperative cheat clients.
- **Packet-Level Coordinate Hard-Clamping**: Overwrites internal X/Y/Z coords of deviating in-flight movement packets directly at the PacketEvents pipeline layer to prevent position leaks.
- **Vehicle Ejection & Dismount Synchronization**: Enforced player dismounting (`player.leaveVehicle()`), vehicle velocity clearing, and mount coordinate syncing during setbacks to prevent BoatFly and horse desync exploits.
- **Action Suppression during Setbacks (`SetbackBlocker`)**: Protocol-level suppression of `INTERACT_ENTITY`, `ATTACK`, and `PLAYER_DIGGING` packets during in-flight states and violation lock.
- **Strict Setback Anchor Invariant**: Enforced `lockedAnchorLocation` during consecutive violation periods to pin setback targets strictly to the pre-cheat baseline.
- **Packet-Level Zero-Velocity Reset**: Dispatches `WrapperPlayServerEntityVelocity(0, 0, 0)` on setbacks to kill client-side keypress momentum.
- **FastEat & Instant Consumption Defense**: Server-side consumption tick counter (32 ticks / 1600ms baseline with 4-tick tolerance), packet spoofing detection, Ping-adaptive RTT lag compensation, and inventory hand synchronization.
- **FastBow & Gatling Bow Defense**: Server-side charge tick counter (20 ticks / 1000ms baseline with 2-tick tolerance), sliding-window burst protection, shot rate limiting, and configurable arrow damage nerf.
- **Double-Layer Combat Mitigation**: Unified attack and damage suppression across Bukkit events and PacketEvents pipeline.
- **Elytra & PacketFly Defense**: Kinetic energy conservation verification ($E=mgh+\frac{1}{2}mv^2$), chestplate durability stripping, and level cruise mitigation.
- **Airborne Ice Momentum Retention (`airborneFromIce`)**: Jump origin tracking to preserve legitimate ice-jump momentum across the entire airborne parabola.
- **Decoupled Configuration**: Extracted all 15 check configuration sections into an independent `checks.yml` file supporting `/argus reload`.

### Changed
- **GrimAC-Grade Anti-Creep & Desync Setback Refactor**: Upgraded setback mechanics to align with GrimAC's desync engine and eliminate creeping desync exploits.
- **Instant Hard-Cut & Zero-Latency Movement Mitigation**: Calibrated instant hard-cut on egregious speed bursts (`diff >= 0.18 || distance > limit * 1.50`) and tightened unconstrained speed buffer maximum from 0.40 to 0.12.
- **Speed Violation Buffer Under Lock**: Dynamically reduces `speedBufferMax` to 0.04 during the 2000ms violation lock period to eliminate creep windows.
- **SpeedCheck Step-Down & Downhill Momentum Allowance**: Elevated downward momentum compensation (`fallDelta >= 0.12`) to 1.30x (up to 0.4225 cap) to accommodate vanilla stair/slab sprint mechanics.
- **Ground Sprint & Bunnyhop Transition Allowance**: Increased ground sprint baseline to 0.325 and granted a 0.39-block transitional impulse during the first 1~2 landing ticks (`groundTicks <= 2`).
- **Dynamic Mid-Air Knockback & Velocity Exemption**: Extended generic knockback/damage exemption window from 500ms to 1000ms, with automatic extension while airborne (`!isOnGround || airTicks > 0`).
- **Fly & Hover Detection Interception**: Tuned launch ascent envelope to 1.05 (+ potion boost), tightened flat-hover detection to 2 ticks, and reduced `max-air-ticks` from 25 to 12.
- **Instant Jesus Mitigation**: Enforced immediate event cancellation on water ground-spoofing and reduced flat-hover and lingering thresholds to 2 and 5 ticks.
- **Timer Drift Handling**: Dropped incoming flying packets when timer drift breaches -70ms deficit limit, while calibrating `DEFICIT_LIMIT` tolerance to -150.0ms for network jitter resiliency.
- **Snapshot Anti-Poisoning**: Blocked positions under speed-buffer suspicion (`speedBuffer > 0.04`) from entering legitimate ground snapshot history.
- **Bukkit In-Flight Cancellation Enforcement**: Explicitly cancels `PlayerMoveEvent` during in-flight states when unaligned to target coordinates.
- **Setback Engine Modularization**: Decoupled monolithic `SetbackManager` into dedicated sub-components (`SafeLocationFinder`, `SetbackPhysics`, `SetbackPacketSync`, `SetbackNotifier`, `PlayerSetbackContext`) under package `com.example.argusac.setback`.
- **Minecraft 26.3 Version Alignment**: Standardized version parsing, enum names (`V26_0`, `V26_3`), and friendly display names across `ServerVersion`, `plugin.yml`, `README.md`, and `.gitignore`.

### Removed
- **Forward Movement Assistance in SpeedCheck**: Completely removed `clampedTo` forward vector calculation in `SpeedCheck`; movement breaches trigger strict in-place cancellation.
- **Invasive Combat Suppression**: Removed weapon cooldown resets and swing charge clearing to restore pure vanilla PvP mechanics.
- **Artificial Setback Cooldown**: Removed the 350ms cooldown on movement physical setbacks, intercepting every violating tick without immunity windows.

### Fixed
- **Jump Apex, Launch & Step-up Jump False Positives (`FlyCheck`)**: Fixed step-up jump misflags by raising `maxAllowedLaunchAscent` to 1.05 and extended parabola decay to cover ticks 5~6 ($v_Y \approx 0.003$ apex).
- **Ground Probe Depth Calibration**: Restored `hasBlockBelow` probe depth to 0.60 blocks starting from offset 0.0, correctly recognizing slab/stair surfaces.
- **Active Hover Suppression Engine Ground Setbacks (`SetbackManager` & `ActiveHoverTracker`)**: Replaced single-point checks with a 5-point AABB probe and added `data.isOnGround()` / `airTicks <= 0` bypass gates, resolving spontaneous `[HARD]` setbacks on ground sprinting.
- **Timer Jitter Buffer & Setback Resync**: Added `timerTracker.reset()` across all setback exit paths (`directCancel`, `executeHardSetbackOnMainThread`, and `SetbackNotifier`) to eliminate cascading `BadPackets (Timer)` false positives.
- **SpeedCheck Step-Down False Setbacks**: Fixed false speed setbacks on 0.16-block vertical drops on stairs and slabs by expanding downhill potential allowance.
- **Mid-Air Knockback Premature Expiration**: Fixed mid-air speed setbacks caused by 500ms knockback expiration during lengthy airborne trajectories.
- **Ice-Jump Airborne Momentum Loss**: Fixed sudden speed flags during long leaps from ice surfaces after 4-tick ground memory expiry.
- **Pre-Loaded Crossbow False Flags (`FastBowCheck`)**: Exempted pre-loaded Crossbows from charge tick requirements.
- **Creative Mode Fast-Break False Flags (`AutoClickerCheck`)**: Added explicit exemptions for Creative and Spectator modes during rapid building and block breaking.
- **NoFall Standable Surface Protection (`PacketPipelineManager`)**: Enhanced ground spoof checks with standable surface scanning (carpets, slabs, stairs, trapdoors, snow layers, scaffolding).
- **Inverted Velocity Response Window (`VelocityCheck`)**: Corrected ping-adaptive RTT response window logic to eliminate initial-move false flags upon taking knockback.
- **Apex & Special Block False Positives (`FlyCheck`)**: Differentiated apex transition from hover and exempted cobwebs, powder snow, and sweet berry bushes.
- **Combat Latency False Positives (`Reach` & `KillAura`)**: Added latency compensation for non-player entities and flick-shot angle tolerance for high-DPI players.
- **Water Jump, Swimming & Sinking Setback Loop Eradication (`FlyCheck` & `SetbackManager`)**: Completely overhauled fluid environment checks by removing the flawed dual-submersion prerequisite that treated head-above-water swimming and surface dolphin hopping as vacuum airborne flight. Liquid environments now reset `airTicks`, deferring fluid anomalies strictly to `JesusCheck`. Furthermore, exempts fluid environments from vacuum gravity acceleration simulation during setbacks, eradicating looping rubberbanding that previously dragged surface-jumping players to the ocean floor.
- **Sprint-Jump & Consecutive Bunnyhop Speed False Flags (`SpeedCheck`)**:
  - Corrected launch-frame packet ordering desynchronization: during jump ascent phases (`ΔY > 0.03`), immediately binds to the launch velocity envelope with a generous 0.44-block limit, absorbing vanilla forward jump impulses (`0.36~0.38` blocks/tick);
  - Elevated ground sprint baseline from 0.325 to 0.340 (covering 45-degree strafe sprint) and landing transitional allowance to 0.40 blocks;
  - Relaxed unconstrained `speed-buffer-max` from 0.12 to industry-standard 0.25 (with decay rate elevated to 0.10) to accommodate consecutive bunnyhop variance while preserving instant hard-cut (`diff >= 0.18 || distance > limit * 1.50`) and tight 0.04 buffer limits under violation locks.
- **Premature Anchor Release**: Fixed an issue where locked anchors were released after 400ms without new cancels, enforcing the full 2000ms anchor lock duration.

## [1.1.5] - 2026-10-17

### Added
- **Vehicle Sync**: Teleported vehicles synchronously with the player during setbacks.

### Changed
- **Setback System Modernization**: Replicated vanilla gravity acceleration during event cancellations.
- **Sub-Block Adherence**: Perfected snapping targets to slabs, snow, and carpets to prevent micro-drops.

## [1.1.4] - 2026-10-17

### Fixed
- **Direct Cancellation**: Abolished immunity windows for instant movement event cancellation.
- **Airborne Hovering**: Added an Active Gravity Task to force descent against suspended packets.
- **Ice SpeedHack**: Capped slippery speeds and reduced ice momentum memory.

## [1.1.3] - 2026-10-17

### Added
- **JesusCheck Module**: Defends against water spoofing, surface hovering, and WaterWalk.

### Changed
- **Liquid Dynamics**: Refined upward swimming tolerances and consecutive jump impulses.

### 修复
- **Fly 误报修复**：修复疾跑跳跃达到波峰阶段时的非法拔高误判。

## [1.1.2] - 2026-10-17

### Fixed
- **Movement Fixes**: Tightened ground detection tolerance and collision box adaptation.
- **Zero-Tick Setback**: Fixed destination desync in HARD mode movement events.
- **Fly Mitigation**: Tightened air-time thresholds and added FastFly mitigation.

## [1.1.1] - 2026-10-17

### Fixed
- **KillAura Defense**: Added attack cooldown resets and snap angle detection.
- **Speed False Positives**: Upgraded bounding box sampling and added ice momentum tracking.
- **AutoClicker Optimization**: Consolidated CPS sampling to raw animation packets.

## [1.1.0] - 2026-10-17

### Added
- **Embedded PacketEvents 2.7.0**: Removed ProtocolLib dependency with shaded relocation, supporting Spigot/Paper 1.12.2 through 1.21.x out-of-the-box.
- **Deterministic Movement Predictor**: Full-envelope kinematic physics simulation calculating theoretical residuals against client inputs.
- **Lag-Compensated Entity History**: 1000ms AABB history tracking to eliminate latency-induced reach false positives.
- **Protocol-level Timer Check**: Nanosecond clock drift balance engine detecting timer speedups and packet bursts.
- **Combat Packet Pipeline**: Native packet listeners for entity interaction and arm animation events.
- **Cross-Version Safe Raytracing**: Added polymorphic line-of-sight ray tracing in `VersionAdapter`.

## [1.0.0] - 2026-10-17

### Added
- **Modular Anti-Cheat Checks**: Core suite covering movement, combat, and block interaction.
- **ElytraFly Suite**: Comprehensive protection against Elytra speed, hover, and flight exploits.
- **Setbacks & Alerts**: Violation management, alerts, and flexible setback configurations.
- **Audit & Tools**: Asynchronous logging, hash-chain verification, and replay tools.
