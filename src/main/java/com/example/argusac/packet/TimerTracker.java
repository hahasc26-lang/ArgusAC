package com.example.argusac.packet;

/**
 * 客户端数据包时钟漂移与 Timer 检测追踪器 (TimerTracker)
 * 采用时钟平衡算法 (Clock Balance Engine)，追踪客户端每个 Flying 包的时间消耗与物理时钟步进，
 * 精准检测 1.05x ~ 2.0x 变速齿轮 (Timer)、包频轰炸 (Packet Spammer) 与快速进食/拉弓 (FastUse)
 */
public class TimerTracker {

    // 时钟平衡值 (毫秒)
    private double balance = 0.0;
    private long lastPacketTimestamp = 0L;

    // 违规累积缓冲
    private double violationBuffer = 0.0;

    // 允许的最大正向时间储备 (防止长时间网络空闲积攒过多可用 balance)
    private static final double MAX_BALANCE = 50.0;
    // 允许的网络卡顿补包恢复最大正余额 (最多吸收约 8 个积压补发包 = 400ms，消除断网抖动后的粘包误报)
    private static final double MAX_CATCHUP_BALANCE = 400.0;
    // 允许的最大赤字容差 (毫秒，对齐标准 3~4 个 Tick 缓冲 -150ms，完全吸收正常网络抖动与回退同步冲击，持续超速仍能在 0.5s 内瞬捕)
    private static final double DEFICIT_LIMIT = -150.0;

    public TimerTracker() {
        reset();
    }

    /**
     * 重置追踪器 (在传送、登录、世界切换或服务器自身卡顿时调用)
     */
    public synchronized void reset() {
        this.balance = 0.0;
        this.lastPacketTimestamp = System.currentTimeMillis();
        this.violationBuffer = 0.0;
    }

    /**
     * 收到客户端 Flying 包时驱动时钟更新
     *
     * @return 若判定为非法 Timer 加速则返回 true，否则返回 false
     */
    public synchronized boolean onFlyingPacket() {
        long now = System.currentTimeMillis();
        if (lastPacketTimestamp == 0L) {
            lastPacketTimestamp = now;
            return false;
        }

        long elapsed = now - lastPacketTimestamp;
        lastPacketTimestamp = now;

        // 如果单次包间隔过大 (> 1000ms)，说明客户端或网络长卡顿/挂起：
        // 保留受控的补包储备额度 (MAX_CATCHUP_BALANCE)，以吸收紧随其后同一 TCP 批次到达的积压包，彻底根除卡顿恢复瞬间的 Timer 误报
        if (elapsed > 1000L) {
            this.balance = Math.min(MAX_CATCHUP_BALANCE, elapsed - 50.0);
            this.violationBuffer = 0.0;
            return false;
        }

        // 每个 Flying 包代表客户端经过了 50ms 的物理运算
        // balance 增加实际物理流逝时间，扣除客户端声明消耗的 50ms
        balance += (elapsed - 50.0);

        // 上限裁剪：若刚才发生了中度网络停顿 (elapsed > 100ms)，允许在 MAX_CATCHUP_BALANCE 内暂存真实等待额度以抵消后续粘包；
        // 若为常规平稳发包，则将正向余额限制在 MAX_BALANCE，防止玩家静止发呆积攒过多额度
        double currentCap = (elapsed > 100L || balance > MAX_BALANCE) ? MAX_CATCHUP_BALANCE : MAX_BALANCE;
        if (balance > currentCap) {
            balance = currentCap;
        }
        if (elapsed >= 40L && elapsed <= 65L && balance > MAX_BALANCE) {
            // 当网络恢复正常平稳发包周期 (40~65ms) 时，平滑回落多余的卡顿补偿储备
            balance = Math.max(MAX_BALANCE, balance - 50.0);
        }

        // 若 balance 严重透支赤字 (客户端发包速度显著快于真实物理时间)
        if (balance < DEFICIT_LIMIT) {
            double deficit = Math.abs(balance - DEFICIT_LIMIT);
            violationBuffer += (deficit / 20.0);

            // 当赤字过大时将 balance 钳位，防止惩罚后长期无法恢复
            balance = DEFICIT_LIMIT;

            if (violationBuffer > 2.0) {
                return true;
            }
        } else {
            // 合法发包平滑衰减缓冲
            if (violationBuffer > 0.0) {
                violationBuffer = Math.max(0.0, violationBuffer - 0.25);
            }
        }

        return false;
    }

    public synchronized double getBalance() {
        return balance;
    }

    public synchronized double getViolationBuffer() {
        return violationBuffer;
    }
}
