package com.example.argusac.prediction;

import org.bukkit.util.Vector;

/**
 * 确定性移动模拟预测结果 (PredictionResult)
 * 记录本次移动与物理模型推算的理论边界包络之间的残差 (Residual) 与缓冲累积状态
 */
public class PredictionResult {

    private final boolean valid;
    private final boolean horizontalValid;
    private final boolean verticalValid;
    private final double horizontalResidual;
    private final double verticalResidual;
    private final double currentHorizontalBuffer;
    private final double currentVerticalBuffer;
    private final Vector bestFittedVelocity;
    private final String reason;

    public PredictionResult(boolean valid, double horizontalResidual, double verticalResidual,
                            double currentHorizontalBuffer, double currentVerticalBuffer,
                            Vector bestFittedVelocity, String reason) {
        this(valid, valid, valid, horizontalResidual, verticalResidual, currentHorizontalBuffer, currentVerticalBuffer, bestFittedVelocity, reason);
    }

    public PredictionResult(boolean valid, boolean horizontalValid, boolean verticalValid,
                            double horizontalResidual, double verticalResidual,
                            double currentHorizontalBuffer, double currentVerticalBuffer,
                            Vector bestFittedVelocity, String reason) {
        this.valid = valid;
        this.horizontalValid = horizontalValid;
        this.verticalValid = verticalValid;
        this.horizontalResidual = horizontalResidual;
        this.verticalResidual = verticalResidual;
        this.currentHorizontalBuffer = currentHorizontalBuffer;
        this.currentVerticalBuffer = currentVerticalBuffer;
        this.bestFittedVelocity = bestFittedVelocity;
        this.reason = reason;
    }

    public static PredictionResult exempt() {
        return new PredictionResult(true, true, true, 0.0, 0.0, 0.0, 0.0, new Vector(0, 0, 0), "Exempt");
    }

    public boolean isHorizontalValid() {
        return horizontalValid;
    }

    public boolean isVerticalValid() {
        return verticalValid;
    }

    public boolean isHorizontalInvalid() {
        return !horizontalValid;
    }

    public boolean isVerticalInvalid() {
        return !verticalValid;
    }

    public boolean isValid() {
        return valid;
    }

    public double getHorizontalResidual() {
        return horizontalResidual;
    }

    public double getVerticalResidual() {
        return verticalResidual;
    }

    public double getCurrentHorizontalBuffer() {
        return currentHorizontalBuffer;
    }

    public double getCurrentVerticalBuffer() {
        return currentVerticalBuffer;
    }

    public Vector getBestFittedVelocity() {
        return bestFittedVelocity;
    }

    public String getReason() {
        return reason;
    }
}
