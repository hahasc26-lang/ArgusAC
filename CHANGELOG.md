# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.4] - 2026-10-17

### Fixed

- **GrimAC-Style Direct In-Event Cancellation**: Completely abolished setback immunity windows; movement violations are cancelled instantly within the move event, immediately pulling airborne cheaters to solid ground and arresting ground moves without rubberbands.
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
