package com.example.argusac.combat;

import com.example.argusac.check.AbstractCheck;

/**
 * 战斗合规检测与分级处置裁决结果 (CombatVerdict)
 * 用于在 Netty 进包阶段 (PacketPlayInUseEntity / ServerboundInteractPacket) 与 Bukkit 兜底阶段统一承载检测结论：
 * - PASS: 合规放行
 * - LOW_CONFIDENCE_DROP: 低确信/微量超出（如网络抖动边缘值），仅静默丢弃数据包 (Drop)，不增加严重 VL
 * - HIGH_CONFIDENCE_VIOLATION: 高确信/明显作弊，丢弃数据包、累加 VL，若伴随移动异常则联动移动预测引擎触发位置矫正 (Setback)
 */
public class CombatVerdict {

    public enum Level {
        PASS,
        LOW_CONFIDENCE_DROP,
        HIGH_CONFIDENCE_VIOLATION
    }

    private static final CombatVerdict PASS_INSTANCE = new CombatVerdict(Level.PASS, null, 0, "");

    private final Level level;
    private final AbstractCheck check;
    private final int vlStep;
    private final String reason;

    public CombatVerdict(Level level, AbstractCheck check, int vlStep, String reason) {
        this.level = level;
        this.check = check;
        this.vlStep = vlStep;
        this.reason = reason != null ? reason : "";
    }

    public static CombatVerdict pass() {
        return PASS_INSTANCE;
    }

    public static CombatVerdict lowConfidenceDrop(AbstractCheck check, String reason) {
        return new CombatVerdict(Level.LOW_CONFIDENCE_DROP, check, 0, reason);
    }

    public static CombatVerdict highConfidence(AbstractCheck check, int vlStep, String reason) {
        return new CombatVerdict(Level.HIGH_CONFIDENCE_VIOLATION, check, Math.max(1, vlStep), reason);
    }

    public boolean isBlocked() {
        return level != Level.PASS;
    }

    public boolean isHighConfidence() {
        return level == Level.HIGH_CONFIDENCE_VIOLATION;
    }

    public boolean isLowConfidenceDrop() {
        return level == Level.LOW_CONFIDENCE_DROP;
    }

    public Level getLevel() {
        return level;
    }

    public AbstractCheck getCheck() {
        return check;
    }

    public int getVlStep() {
        return vlStep;
    }

    public String getReason() {
        return reason;
    }
}

