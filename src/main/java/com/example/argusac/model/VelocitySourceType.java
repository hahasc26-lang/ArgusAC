package com.example.argusac.model;

/**
 * 合法高机动与速度来源枚举
 */
public enum VelocitySourceType {
    /** 鞘翅滑翔 */
    ELYTRA,
    /** 烟花火箭推进 */
    FIREWORK,
    /** 激流三叉戟 */
    RIPTIDE,
    /** 末影珍珠传送 */
    ENDER_PEARL,
    /** 活塞或方块推动 */
    PISTON,
    /** 实体受伤害/爆炸击退 */
    EXPLOSION,
    /** 风弹与风涌推力 (1.21+ / 26.x+) */
    WIND_CHARGE,
    /** 气泡柱向上浮力 */
    BUBBLE_COLUMN,
    /** 载具移动 */
    VEHICLE,
    /** 外部其他插件传送 */
    EXTERNAL_TELEPORT,
    /** 回退网络位置校正 */
    SETBACK_CORRECTION
}
