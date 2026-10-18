# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-10-23

### Added
- **FastBow & Gatling Bow Defense (快速射箭与加特林弓全面防御)**: Server-side charge tick counter (20 ticks / 1000ms baseline with 2-tick jitter tolerance), sliding-window burst & packet spoil protection, shot rate cooldown limiting, and configurable arrow nerf/cancellation.
- **GrimAC Teleport Confirmation & In-Flight Lock (流水号事务与抗震颤回退)**: Monotonic sequence IDs with PacketEvents `TELEPORT_CONFIRM` packet ACK tracking to eliminate rubberbanding loops during latency windows.
- **Setback Modularization (回退引擎模块化)**: Refactored `SetbackManager` into dedicated sub-components (`SafeLocationFinder`, `SetbackPhysics`, `SetbackPacketSync`, `SetbackNotifier`, `PlayerSetbackContext`) under `com.example.argusac.setback`.
- **Decoupled Configuration (独立检测配置)**: Separated all 15 check configs into `checks.yml` with dynamic reload support (`/argus reload`).
- **Double-Layer Combat Mitigation (双层战斗静默拦截)**: Unified attack and damage mitigation across Bukkit events and PacketEvents pipeline.
- **Elytra & PacketFly Defense (鞘翅滑翔全面防御)**: Kinetic energy conservation ($E=mgh+\frac{1}{2}mv^2$), durability stripping, and cruise mitigation.

### Changed
- **Pure Vanilla PvP Experience (移除侵入式战斗抑制)**: Removed weapon cooldown and charging resets to preserve authentic PvP mechanics.
- **Strict Motion Zeroing (回退动量彻底清零)**: Resets server-side physics velocity and residual client motion upon setback to prevent follow-up false flags.

### Fixed
- **Velocity Anti-KB Inverted Logic (击退响应窗口修复)**: Introduced ping-adaptive RTT response window to eliminate initial-move false flags.
- **Speed Buffer Clamping (速度平滑缓冲区截断)**: Clamps excessive movement smoothly below `min-vl`, preventing false rubberbanding on stairs and lag spikes.
- **Fly & Hover Detection (滞空与顶点误报消除)**: Distinguishes jump apex ($v_Y \approx 0.003$) via 3-tick verification and exempts cobwebs, powder snow, and berries.
- **Combat False Positives (战斗检测误报消除)**: Added latency compensation for mobs (Reach) and flick-shot tolerance for high-DPI players (KillAura).
- **Movement Environment Adaptations (环境与状态误报修复)**: Added Depth Strider and liquid current tolerance (Jesus), and 2-tick debounce for sneak-sprint transitions (BadPackets).

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
- **Embedded PacketEvents 2.7.0 (内置数据包框架)**: Shaded protocol pipeline supporting Paper/Spigot 1.12.2 through 1.21.x.
- **Deterministic Movement Predictor (确定性物理预测引擎)**: Kinematic physics residual simulation.
- **Lag-Compensated Entity History (时空回溯历史盒)**: 1000ms AABB snapshot rollback for accurate reach checks.
- **JesusCheck & Timer Engine (水上行走与高精时钟检测)**: Liquid ground spoofing and nanosecond drift balance checks.
