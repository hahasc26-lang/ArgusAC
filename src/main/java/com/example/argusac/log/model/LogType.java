package com.example.argusac.log.model;

/**
 * 统一事件类型枚举 (严格限定 5 种核心类型)
 */
public enum LogType {
    VIOLATION,     // 违规触发与回退执行
    SETBACK_FAIL,  // 回退操作失败 (如位置被外部方块卡死阻挡)
    SESSION,       // 玩家会话变更 (加入/离开/跨世界)
    ADMIN,         // 管理员操作审计 (指令操作/配置重载/数据导出/签名校验等)
    INTEGRITY      // 链式哈希周期性检查点
}
