# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-10-18

### Added

- **Independent Check Configuration Decoupling (`checks.yml`)**:
  1. **Decoupled Checks Configuration**: Created an independent `checks.yml` file under the plugin data directory containing complete settings and thresholds for all 14 detection modules;
  2. **Config Structure Cleanup**: Removed the redundant `checks:` block from `config.yml`, keeping `config.yml` focused strictly on core system settings, setback modes, punishment escalation, audit logging, and i18n;
  3. **Full Lifecycle Integration**: Integrated `checksConfig` loading and saving directly into `/argus reload` and `/argus toggle` command workflows.
- **Dedicated Double-Layer Combat Mitigation Handler (`CombatMitigationHandler`)**:
  1. **Centralized Combat Defense**: Introduced `com.example.argusac.combat.CombatMitigationHandler` for unified, double-layer combat attack and damage cancellation;
  2. **Bukkit & Netty Dual Protection**: Simultaneously intercepts Bukkit `EntityDamageByEntityEvent` and PacketEvents `WrapperPlayClientInteractEntity (ATTACK)` packets;
  3. **Zero-Damage Defense**: Enforces当帧 `event.setCancelled(true)` and sets `event.setDamage(0.0)` defensively to eliminate damage leaks, knockback, armor durability loss, and sweep attacks;
  4. **Pure Silent Cancellation**: Cancels illegal combat interactions silently without sending disruptive ActionBar warnings or sounds to attackers, maintaining clean staff alerts and audit logs.
- **CANCEL Mode Hardening & Dual Instant Mitigation**:
  1. **Dual Instant Mitigation**: Upgraded `SetbackManager.directCancel` to simultaneously set `moveEvent.setTo(cancelTarget)` and `moveEvent.setCancelled(true)` at the Bukkit event layer, cleanly severing the server physics pipeline; concurrently added `sendPositionSyncPacket` sending protocol-level position reset packets via PacketEvents to forcefully break client-side physical prediction;
  2. **Continuous Locked Anchor**: Introduced a locked anchor state machine in `PlayerSetbackContext`; players repeatedly attempting to break through cancellation barriers or force-fly within a short window (4 seconds) are strictly locked to their initial violation coordinate frame-by-frame with zero cooldown, completely closing inchworming exploits;
  3. **Legitimate Movement Safe Release**: Smoothly releases the locked anchor once a player resumes clean movement and stands solidly grounded for over 400ms; automatically clears anchor state upon disconnect, death, respawn, world transfer, or external legitimate teleports;
  4. **Natural Gravity Drag for Airborne Force-Fly**: For airborne fly and anti-gravity cheats, horizontally locks X/Z axes to the initial anchor while strictly suppressing upward vertical climbs and applying natural vanilla gravity descent, dragging cheaters toward the ground rather than creating pseudo-platforms;
  5. **README & Config Recommended Badging**: Formally added the `(Recommended)` label to `CANCEL` setback mode in `README.md` and `config.yml`.

### Changed

- **Abolished Combat Throttle & Weapon Cooldown Resets**:
  1. Completely removed invasive `combat-throttle` (disarm/temporary attack ban) and `reset-cooldown` (forced weapon charge reset) from `KillAuraCheck`, `ReachCheck`, and `PlayerData`;
  2. Preserved authentic vanilla PvP mechanics and hit feeling without false disarm penalties.

### Fixed

- **Cancellation Latency & Exploit Loophole Fixes**:
  1. Fixed loopholes where the previous cancellation mechanism did not send immediate packet-level synchronization, allowing cheats to creep forward during packet round-trip delays;
  2. Fixed target location inaccuracy in `PlayerEventListener` upon cancellation, standardizing on `event.getTo()` to eliminate lingering residuals;
  3. Zeroed vehicle velocity during cancellation setbacks to prevent vehicles from breaching barriers.
- **Zero-Latency Fly & Anti-Gravity Hover Detection**:
  1. **Instant Illegal Ascent Detection**: Enforced theoretical jump decay curves; vertical climb past peak jump trajectory (`airTicks >= 5`) is capped at `<= 0.001`, instantly flagging and blocking jetpack/fly cheats on the very first frame;
  2. **Eliminated 600ms (12 ticks) Hover Lag**: Reduced flat hover detection threshold to `airTicks >= 4` (`|deltaY| < 0.005`) and anti-gravity drift to `airTicks >= 6` (`deltaY > -0.05`), providing near-instant blocking against flight cheats.
- **Block Placement & Jump-Place Scaffold Fly False Positive Fix & Optimization**:
  1. Recorded real-time block placement timestamps (`lastPlaceTime`) for responsive movement analysis;
  2. Expanded `FlyCheck` ground probe depth to 0.60 blocks with multi-point boundary sampling;
  3. Fixed false ground stripping on step-up and newly placed block ascents;
  4. Granted 1.2-block supporting surface grace and airTicks reset for recent block placements (1200ms window), completely eliminating Fly false positives during scaffold/jump-placing.



## [1.1.4] - 2026-10-17

### Fixed

- **GrimAC-Style Direct In-Event Cancellation & Setback Modernization**:
  1. Completely abolished setback immunity windows; movement violations are cancelled instantly within the move event.
  2. **Vanilla Gravity Acceleration Curve Fitting**: Dynamically simulates authentic free-fall acceleration ($v_Y = (v_{Y,prev} - 0.08) \times 0.98$) during air cancellation and Active Gravity Tasks, eliminating constant-speed artificial sinking.
  3. **Precision Sub-Block Terrain Adherence**: Exact collision shape Top-Y calculation via `CompatBoundingBox`, snapping setback targets to the real surface of slabs (0.5), snow layers, and stairs to eliminate post-setback micro-drops.
  4. **Vehicle & Mount Synchronization**: Synchronized vehicle velocity zeroing and teleportation during setbacks, keeping players smoothly inside their boats/minecarts/horses.
  5. **Teleport Sequence ID Ack**: Monotonic `SequenceId` tracking on `PendingCorrection` to prevent feedback loops and rubberband storms during high-ping transition windows.
  6. **Low-VL Ping-Adaptive Jitter Clamping**: Vector projection clamping in `SpeedCheck` for sub-threshold jitter (`diff < 0.08`), allowing high-ping legitimate players to move smoothly forward.
- **Eliminated Airborne Hovering Exploits (Dual Gravity Enforcement)**:
  1. Fixed zero-delta movement (`!posChanged`) bypass where stationary hovering in air was skipped; stationary airborne moves now route into `FlyCheck` directly.
  2. Fixed sub-50ms rapid packets incorrectly resetting airTicks accumulation.
  3. Added server-side **Active Gravity Enforcement Engine (Active Gravity Task)** running every 2 ticks on the main thread, forcing downward gravity descent even if cheating clients completely suspend packet sending (Blink/Freecam/Suspend).
- **Wurst Slippery / Ice Simulation SpeedHack Fix**:
  1. Implemented a strict vanilla kinematic envelope distinguishing real ice blocks from normal ground (normal flat sprint strictly capped at 0.31 blocks/tick).
  2. Reduced post-ice inertia memory (`iceTicks`) from 25 ticks to 4 ticks, eliminating long sliding bypass windows.
  3. Implemented instant direct event cancellation on speed violations to stop speed runners in-place without buffer creep.
- **Fly & Hover Detection Hardening**: Enforced post-jump cycle (airTicks > 12) gravity descent checks, closing bypasses on slow ascent and micro-fall floating.
- **AutoClicker False Positives**: Added continuous mining state tracking to exempt block breaking from combat CPS calculations.
- **Jesus False Positives**: Refined water surface tracking to only accumulate on unsubmerged surface floating, preventing false flags during normal swimming and wading.
- **Scaffold False Positives**: Added horizontal footprint bounding checks to avoid false flags when placing blocks ahead or sideways at Y-1.
- **Packet Pipeline & NoFall False Positives**: Added comprehensive exemptions for creative/spectator modes, flight, vehicles, elytra, and climbable/liquid environments.

## [1.1.3] - 2026-10-17

### Added

- Added `JesusCheck` module for water ground spoofing, solid surface hovering, and WaterWalk speeding.
- Added bubble column detection and dynamic impulse exemptions.

### Changed

- Refined liquid dynamics, upward swimming equations, and vertical physics prediction with dynamic tolerances.
- Adjusted ground acceleration constant to vanilla standard and tuned consecutive jump impulses and friction.
- Integrated `JesusCheck` into the setback engine and localization system.

### Fixed

- Fixed false positives in `FlyCheck` during sprint-jump ascent and crest phases.
- Adjusted `IllegalAscent` thresholds and improved water transition handling.

## [1.1.2] - 2026-10-17

### Fixed

- **Movement Prediction & Jump Fixes**: Tightened ground detection tolerance to fix impulse loss during sprint-jumping; added collision box adaptation for fences and walls.
- **Zero-Tick Setback Mitigation**: Fixed HARD mode destination desync in movement events, preventing post-violation displacement leaks.
- **Fly Mitigation Hardening**: Tightened air-time thresholds and introduced `FastFly` horizontal flight mitigation.
- **Compatibility**: Added custom `walkSpeed` adaptation and enhanced multi-layer ladder/web detection.

## [1.1.1] - 2026-10-17

### Fixed

- **KillAura Active Defense**: Added damage mitigation, attack cooldown resets, combat throttling, and snap angle detection.
- **Speed False Positives**: Upgraded to 5-point bounding box sampling for carpets, slabs, and stairs; added jump protection and ice momentum tracking.
- **AutoClicker Optimization**: Consolidated CPS sampling to raw animation packets to eliminate tick-merging inflation.
- **Cross-Version Stability**: Fixed runtime errors caused by missing Material enums and methods on legacy versions.

## [1.1.0] - 2026-10-17

### Added

- **Embedded PacketEvents 2.7.0**: Removed ProtocolLib dependency with shaded relocation, supporting Spigot/Paper 1.12.2 through 1.21.x out-of-the-box.
- **Deterministic Movement Predictor**: Full-envelope kinematic physics simulation calculating theoretical residuals against client inputs.
- **Lag-Compensated Entity History**: 1000ms AABB history tracking to eliminate latency-induced reach false positives.
- **Protocol-level Timer Check**: Nanosecond clock drift balance engine detecting timer speedups and packet bursts.
- **Combat Packet Pipeline**: Native packet listeners for entity interaction and arm animation events.

## [1.0.0] - 2026-10-17

### Added

- Modular anti-cheat core covering Movement, Combat, World, and Packet validations.
- Elytra flight check suite covering speed, illegal ascent, hovering, fireworks, and fall damage exploits.
- Setback engine supporting HARD/SOFT modes, fallback locations, and adaptive immunity windows.
- Violation level tracking, dynamic staff alerts, and automated punishment dispatching.
- Asynchronous JSON Lines audit logging with SHA-256 hash chains and movement playback support.
