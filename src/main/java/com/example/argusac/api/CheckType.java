package com.example.argusac.api;

import com.example.argusac.i18n.I18n;
import org.bukkit.command.CommandSender;

/**
 * 检测分类枚举
 */
public enum CheckType {
    MOVEMENT("check.type.movement", "Movement"),
    COMBAT("check.type.combat", "Combat"),
    WORLD("check.type.world", "World"),
    PACKET("check.type.packet", "Packet");

    private final String i18nKey;
    private final String fallbackName;

    CheckType(String i18nKey, String fallbackName) {
        this.i18nKey = i18nKey;
        this.fallbackName = fallbackName;
    }

    public String getDisplayName() {
        try {
            return I18n.getServer(i18nKey);
        } catch (Exception e) {
            return fallbackName;
        }
    }

    public String getDisplayName(CommandSender sender) {
        try {
            return I18n.get(sender, i18nKey);
        } catch (Exception e) {
            return fallbackName;
        }
    }

    public String getI18nKey() {
        return i18nKey;
    }
}


