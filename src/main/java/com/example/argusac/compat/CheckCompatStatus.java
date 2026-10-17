package com.example.argusac.compat;

import com.example.argusac.i18n.I18n;
import org.bukkit.command.CommandSender;

public enum CheckCompatStatus {
    FULL("&aFULL", "compat.status.full"),
    DEGRADED("&eDEGRADED", "compat.status.degraded"),
    DISABLED("&cDISABLED", "compat.status.disabled");

    private final String fallbackDisplay;
    private final String i18nKey;

    CheckCompatStatus(String fallbackDisplay, String i18nKey) {
        this.fallbackDisplay = fallbackDisplay;
        this.i18nKey = i18nKey;
    }

    public String getDisplay() {
        try {
            return I18n.getServer(i18nKey);
        } catch (Exception e) {
            return fallbackDisplay;
        }
    }

    public String getDisplay(CommandSender sender) {
        try {
            return I18n.get(sender, i18nKey);
        } catch (Exception e) {
            return fallbackDisplay;
        }
    }

    public String getDescription() {
        return getDisplay();
    }

    public String getI18nKey() {
        return i18nKey;
    }
}


