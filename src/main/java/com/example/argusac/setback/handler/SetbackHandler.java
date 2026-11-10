package com.example.argusac.setback.handler;

import com.example.argusac.api.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 违规回退与位移惩戒模式处理器通用契约
 */
public interface SetbackHandler {

    /**
     * 执行指定模式的回退或位移阻断处理
     *
     * @param player       违规玩家
     * @param check        触发的检测模块
     * @param currentVl    当前违规等级
     * @param reason       违规细节原因
     * @param moveEvent    关联的移动事件 (可为 null)
     * @return 是否成功执行了回退或阻断处置
     */
    boolean handle(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent);
}

