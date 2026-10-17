package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;

/**
 * NoFall ground-spoof check.
 * Client onGround claims are validated by PacketPipelineManager against movement state.
 */
public class NoFallCheck extends AbstractCheck {

    public NoFallCheck(ArgusACPlugin plugin) {
        super(plugin, "NoFall", CheckType.MOVEMENT);
    }
}
