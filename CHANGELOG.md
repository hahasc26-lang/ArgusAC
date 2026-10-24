# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.1] - 2026-10-24

### Added
- **Heavy Mace Combat & Damage Exploit Check (`MaceCheck` / `MaceDMG`)**: Comprehensive defense suite against Minecraft 1.21+ Mace exploits (Wurst MaceDMG, LiquidBounce MaceKill, and fake-fall packet spoofing).
- **True Physical Apex Verification**: Independently tracks player's true maximum airborne altitude (`trueApexY`) since leaving the ground, enforcing that claimed fall distance cannot exceed $(\text{trueApexY} - \text{currentY}) + \epsilon$.
- **Ground Smash Interception**: Detects and cancels smash attacks executed while grounded or with insufficient physical drop ($< 1.5$ blocks), eliminating flat-ground one-hit kill exploits.
- **Kinematic Gravity & Fall Time Constraint**: Enforces physical fall rate limits based on elapsed airborne ticks ($D_{\max} \approx \text{airTicks} \times 3.92 + 2.0$), preventing instant 1-tick fall distance injection.
- **Theoretical Damage Ceiling & Cap Protection**: Computes exact theoretical damage limits based on base damage, Density I~V enchantments, Strength potion effects, and critical modifiers with configurable hard damage caps.
- **Smash Rate Limiting**: Enforces a minimum interval (default 350ms) between consecutive smash attacks to prevent burst smash packet exploits.
- **Cross-Version Graceful Evaluation**: Automatically marks `MaceCheck` as disabled on pre-1.21 servers and full performance on 1.21+ runtimes.

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

---

## [1.1.4] - 2026-10-17

### Fixed
- **Direct Movement Cancellation (当帧即时直接取消)**: Replaced delay windows with in-event cancellation and authentic gravity curve simulation ($v_Y = (v_{Y,prev} - 0.08) \times 0.98$).
- **Sub-Block Terrain Adherence (半方块精准贴合)**: Snaps setback targets to exact top surfaces of slabs, stairs, and snow layers.
- **Active Gravity Enforcement (主动重力监管引擎)**: Server-side task forces descent for packet-suspended clients (Blink/Freecam).
- **Wurst Ice SpeedHack Fix (滑冰加速漏洞修复)**: Strict ground kinematic speed envelope and reduced post-ice inertia to 4 ticks.
- **False Positive Hardening (综合误报优化)**: AutoClicker mining exemptions, Jesus wading tolerance, and Scaffold horizontal footprint checks.

---

## [1.1.0 - 1.1.3] - 2026-10-17

### Added
- **Embedded PacketEvents 2.7.0 (内置数据包框架)**: Shaded protocol pipeline supporting Paper/Spigot 1.12.2 through 26.3.
- **Deterministic Movement Predictor (确定性物理预测引擎)**: Kinematic physics residual simulation.
- **Lag-Compensated Entity History (时空回溯历史盒)**: 1000ms AABB snapshot rollback for accurate reach checks.
- **JesusCheck & Timer Engine (水上行走与高精时钟检测)**: Liquid ground spoofing and nanosecond drift balance checks.
