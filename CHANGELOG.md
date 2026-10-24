# Changelog

## [1.2.1] - 2026-10-24
### Added
- **Netty Packet-Level Combat Mitigation**: Intercepts attacks at the Netty protocol layer with zero-latency drops.
- **Downstream Broadcast Suppression**: Silences entity status, visual animations, and sound effects on blocked attacks.
- **Hierarchical Mitigation Strategy**: Employs silent drops for low-confidence flags and strict setbacks for severe violations.
- **Heavy Mace Exploit Defense**: Prevents fake-fall packet spoofing and flat-ground smash exploits.
### Fixed
- **Slowness Sprint-Jumping**: Fixed false positive speed violations under Slowness potion effects.
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