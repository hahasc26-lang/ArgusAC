package com.example.argusac.setback;

import org.bukkit.Location;

/**
 * 服务端主动重力监管状态追踪器 (ActiveHoverTracker)
 * 解决作弊端挂起移动发包 (Blink/Suspend/Hover/Freecam) 导致 PlayerMoveEvent 不触发从而悬浮在空中的终极根源。
 */
public class ActiveHoverTracker {

    private int airborneTicks = 0;
    private double lastY = Double.NaN;
    private long airborneStartTime = 0L;

    public void reset() {
        this.airborneTicks = 0;
        this.lastY = Double.NaN;
        this.airborneStartTime = 0L;
    }

    public void tick(Location loc, long now) {
        if (loc == null) return;
        if (airborneStartTime == 0L) {
            airborneStartTime = now;
        }
        airborneTicks += 2; // 每 2 tick 巡检一次

        double curY = loc.getY();
        if (!Double.isNaN(lastY)) {
            double dy = curY - lastY;
            // 若出现正常下落 (下落幅度至少 0.06)，说明重力在起效
            if (dy < -0.06) {
                airborneTicks = Math.max(0, airborneTicks - 2);
            }
        }
        this.lastY = curY;
    }

    public boolean isIllegalHover(int thresholdTicks, long thresholdMs) {
        long now = System.currentTimeMillis();
        return airborneTicks >= thresholdTicks && (now - airborneStartTime >= thresholdMs);
    }

    public int getAirTicks() {
        return airborneTicks;
    }
}
