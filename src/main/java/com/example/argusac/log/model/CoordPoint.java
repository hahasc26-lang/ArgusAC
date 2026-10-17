package com.example.argusac.log.model;

import com.google.gson.JsonObject;
import org.bukkit.Location;

/**
 * 结构化紧凑坐标模型 (统一格式，yaw/pitch 仅在必要时包含)
 */
public record CoordPoint(String world, double x, double y, double z, Float yaw, Float pitch) {

    public CoordPoint(String world, double x, double y, double z) {
        this(world, x, y, z, null, null);
    }

    public static CoordPoint fromLocation(Location loc, boolean includeRotations) {
        if (loc == null) return null;
        String w = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        double rx = Math.round(loc.getX() * 1000.0) / 1000.0;
        double ry = Math.round(loc.getY() * 1000.0) / 1000.0;
        double rz = Math.round(loc.getZ() * 1000.0) / 1000.0;
        if (includeRotations) {
            float ryaw = Math.round(loc.getYaw() * 10.0f) / 10.0f;
            float rpitch = Math.round(loc.getPitch() * 10.0f) / 10.0f;
            return new CoordPoint(w, rx, ry, rz, ryaw, rpitch);
        }
        return new CoordPoint(w, rx, ry, rz);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("world", world);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        if (yaw != null) obj.addProperty("yaw", yaw);
        if (pitch != null) obj.addProperty("pitch", pitch);
        return obj;
    }

    public static CoordPoint fromJson(JsonObject obj) {
        if (obj == null) return null;
        String world = obj.has("world") ? obj.get("world").getAsString() : "world";
        double x = obj.has("x") ? obj.get("x").getAsDouble() : 0.0;
        double y = obj.has("y") ? obj.get("y").getAsDouble() : 0.0;
        double z = obj.has("z") ? obj.get("z").getAsDouble() : 0.0;
        Float yaw = obj.has("yaw") ? obj.get("yaw").getAsFloat() : null;
        Float pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : null;
        return new CoordPoint(world, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return String.format("(%.1f,%.1f,%.1f)", x, y, z);
    }
}
