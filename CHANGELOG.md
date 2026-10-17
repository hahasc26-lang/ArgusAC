# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.5] - 2026-10-17

### Enhanced

- **Comprehensive Setback System Modernization & Player Experience Overhaul**:
  1. **Vanilla Gravity Acceleration Curve Fitting**: Dynamically computes realistic Minecraft free-fall acceleration ($v_Y = (v_{Y,prev} - 0.08) \times 0.98$) during in-event air cancellation and server-side Active Gravity Tasks, completely eliminating constant-speed artificial sinking and visually matching authentic vanilla descent.
  2. **Precision Sub-Block Terrain Adherence**: Overhauled `findSafeGroundNear` and `findSafeGroundBelow` with exact collision shape Top-Y calculation via `CompatBoundingBox`, perfectly snapping setback targets to the real surface of slabs (0.5), snow layers (0.125*N), stairs, and carpets to eliminate the 0.5m post-setback micro-drop.
  3. **Vehicle & Mount Axis Synchronization**: Integrated synchronized vehicle velocity zeroing and entity teleportation across direct cancellation and hard setbacks, keeping players smoothly inside their boats/minecarts/horses instead of forcibly dismounting them into voids or liquids.
  4. **Teleport Sequence ID Acknowledgement**: Integrated monotonic `SequenceId` tracking on `PendingCorrection` to prevent feedback loops and rubberband storms caused by high-ping stale packets during transition windows.
  5. **Low-VL Ping-Adaptive Jitter Vector Clamping**: Implemented vector projection clamping in `SpeedCheck` for sub-threshold jitter (`diff < 0.08`), allowing high-ping legitimate players to move smoothly forward at max allowable speed without jarring wall hitches.

## [1.1.4] - 2026-10-17

### Fixed

- **GrimAC-Style Direct In-Event Cancellation**: Completely abolished setback immunity windows; movement violations are cancelled instantly within the move event, enforcing vanilla gravity decay and horizontal arresting for airborne exploits (near-ground snapping to prevent downward teleport/phase exploits and micro-fall bypasses).
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
