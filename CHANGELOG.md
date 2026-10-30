# Changelog

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
- **Combat Accuracy**: Resolved angle calculation errors and stale-angle blindspot false flags.
### Changed
- **Combat Architecture**: Refactored to a centralized packet-level combat engine, demoting Bukkit events to fallbacks.

## [1.2.0] - 2026-10-23
### Added
- **Setback Engine Overhaul**: Isolated negative teleport IDs and resynchronized uncooperative cheat clients.
- **Action Suppression**: Blocked interaction and digging packets during setbacks.
- **New Exploits Defense**: Defended against FastEat, FastBow, and packet-based Elytra/BoatFly exploits.
### Changed
- **Movement Mitigation**: Improved instant hard-cut calibration and expanded ground sprint allowances.
- **Hover Interception**: Tightened Fly/Hover detection thresholds and enforced immediate Jesus mitigation.
### Removed
- **Invasive Systems**: Removed artificial setback cooldowns and invasive combat cooldown resets.
### Fixed
- **False Positives**: Eliminated false setbacks for step-up jumps, stair drops, and water surface hopping.

## [1.1.5] - 2026-10-17
### Enhanced
- **Setback System Modernization**: Replicated vanilla gravity acceleration during event cancellations.
- **Sub-Block Adherence**: Perfected snapping targets to slabs, snow, and carpets to prevent micro-drops.
- **Vehicle Sync**: Teleported vehicles synchronously with the player during setbacks.

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
### Fixed
- **Fly False Positives**: Fixed illegal ascent misflags during sprint-jump crest phases.

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