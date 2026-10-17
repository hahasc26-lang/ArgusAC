# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-17

### Added

- **Modular Anti-Cheat Checks**: Added configurable checks for movement, combat, block interaction, and suspicious packet data, including Speed, Fly, NoFall, InventoryMove, Reach, AutoClicker, KillAura, Velocity, FastBreak, Nuker, Scaffold, and BadPackets.
- **ElytraFly Detection Suite**: Added eight configurable sub-checks for flight speed, ascent, hovering, firework boosts, glide-state consistency, timer behavior, rapid glide toggling, and fall-damage abuse.
- **Last-Known-Legitimate-Location Setbacks**: Added `HARD` and `SOFT` setback modes, per-check overrides, fallback locations, configurable immunity windows, and setback notifications.
- **Violation Management and Alerts**: Added violation-level tracking and decay, configurable staff alerts, and punishment actions.
- **Audit Logging and Investigation Tools**: Added asynchronous JSON Lines audit logging, log retention and compression, SHA-256 hash-chain verification, recent and historical log queries, player data export, and movement replay output.
- **Cross-Version Compatibility Layer**: Added compatibility detection and graceful degradation for Bukkit/Spigot/Paper server versions targeted by the plugin, with runtime status available through `/argus compat`.
- **Internationalization**: Added bundled English and Simplified Chinese language files with optional client-locale message selection.
- **Administration Commands**: Added `/argus` (alias `/ac`, `/argusac`) commands for configuration reloads, check management, violation queries, log inspection, compatibility diagnostics, setback controls, and audit data management.
