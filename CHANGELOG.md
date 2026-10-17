# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-10-17

### Major Architecture Upgrade (GrimAC-Grade Architecture Evolution)

- **Native PacketEvents 2.7.0 integration**: Removed the external ProtocolLib dependency and bundled PacketEvents with shaded package relocation, enabling out-of-the-box use on Spigot/Paper 1.12.2–1.21.x without a separate dependency.
- **Deterministic movement prediction engine**:
  - Uses Minecraft's original movement physics equations to evaluate all eight directional input combinations, sprinting/walking/sneaking, jump impulses, and vertical gravity.
  - Introduces theory-based residual and buffer accumulation checks inspired by GrimAC's industrial-grade approach, replacing coarse movement-distance thresholds to detect mini-speed and mini-jump cheats.
- **Lag-compensated entity history tracker**:
  - Stores per-tick three-dimensional axis-aligned bounding box (AABB) snapshots for target entities over the most recent 1,000 ms.
  - Rewinds to the historical hitbox visible from the attacker's perspective based on the attacker's actual ping when an attack occurs. This prevents false reach flags caused by targets moving away after knockback and constrains reach checks to the vanilla theoretical limit of 3.00 blocks.
- **Protocol-level timer clock-drift engine**:
  - Applies a nanosecond-resolution clock-balance algorithm with a 50 ms per-tick budget at the packet level to detect timer speeds from 1.05x to 2.0x, fast eating, and packet flooding.
- **Combat packet pipeline**:
  - Listens directly to entity-interaction attack and arm-swing animation packets at the network layer, avoiding distorted AutoClicker CPS and interval-variance sampling caused by Bukkit's main-thread tick aggregation.
- **Cross-version safe line-of-sight ray tracing**:
  - Adds polymorphic ray tracing to `VersionAdapter`: uses native `world.rayTraceBlocks` on 1.13+ and safe step-based ray tracing on 1.12.2, preventing `NoSuchMethodError` crashes on older versions.

## [1.0.0] - 2026-10-17

### Added

- **Modular anti-cheat checks**: Added configurable movement, combat, block-interaction, and suspicious-packet checks, including Speed, Fly, NoFall, InventoryMove, Reach, AutoClicker, KillAura, Velocity, FastBreak, Nuker, Scaffold, and BadPackets.
- **ElytraFly detection suite**: Added eight configurable subchecks covering flight speed, abnormal ascent, hovering, firework boosts, glide-state consistency, timer behavior, rapid glide toggling, and fall-damage abuse.
- **Last-known-legitimate-location setbacks**: Added `HARD` and `SOFT` setback modes, per-check overrides, fallback legitimate locations, configurable immunity windows, and setback notifications.
- **Violation management and alerts**: Added violation-level tracking and decay, configurable staff alerts, and punishment actions.
- **Audit logging and investigation tools**: Added asynchronous JSON Lines audit logging, log retention and compression, SHA-256 hash-chain verification, recent and historical log queries, player data exports, and movement replays.
- **Cross-version compatibility layer**: Added compatibility detection and graceful degradation for the Bukkit/Spigot/Paper server versions targeted by the plugin, with runtime status available through `/argus compat`.
- **Internationalization**: Added English and Simplified Chinese language files, with optional message selection based on each player's client language.
- **Administration commands**: Added `/argus` (aliases `/ac` and `/argusac`) commands for configuration reloads, check management, violation queries, log review, compatibility diagnostics, setback controls, and audit data management.
