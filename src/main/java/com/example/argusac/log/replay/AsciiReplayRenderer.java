package com.example.argusac.log.replay;

import com.example.argusac.log.model.CoordPoint;
import com.example.argusac.log.model.LogEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯字符 ASCII 轨迹与高度条轻量渲染器
 * 无需图形界面、零 Web 前端依赖、完全基于 Minecraft 聊天栏
 */
public class AsciiReplayRenderer {

    private static final int GRID_SIZE = 17; // 17x17 适中聊天框网格
    private static final char[] DENSITY_MAP = {' ', '.', ':', '-', '=', '+', '*', '#', '%'};

    public List<String> render(LogEntry violation, boolean sideView) {
        return render(null, violation, sideView);
    }

    public List<String> render(org.bukkit.command.CommandSender sender, LogEntry violation, boolean sideView) {
        List<String> output = new ArrayList<>();
        if (violation == null || violation.getFrom() == null || violation.getTo() == null) {
            output.add(com.example.argusac.i18n.I18n.get(sender, "command.replay.no-coords"));
            return output;
        }

        CoordPoint from = violation.getFrom();
        CoordPoint to = violation.getTo();

        String viewType = sideView 
                ? com.example.argusac.i18n.I18n.get(sender, "command.replay.view-side") 
                : com.example.argusac.i18n.I18n.get(sender, "command.replay.view-top");

        output.add(com.example.argusac.i18n.I18n.get(sender, "command.replay.header",
                "player", violation.getPlayer(),
                "view", viewType));
        output.add(com.example.argusac.i18n.I18n.get(sender, "command.replay.info",
                "check", violation.getCheck(),
                "severity", violation.getSeverity() != null ? violation.getSeverity().name() : "LOW",
                "mode", violation.getMode() != null ? violation.getMode() : "NONE"));
        output.add(com.example.argusac.i18n.I18n.get(sender, "command.replay.coords",
                "from", from.toString(),
                "to", to.toString()));

        char[][] grid = new char[GRID_SIZE][GRID_SIZE];
        for (char[] row : grid) Arrays.fill(row, '·');

        // 计算缩放映射
        double x1 = from.x();
        double z1 = sideView ? from.y() : from.z();
        double x2 = to.x();
        double z2 = sideView ? to.y() : to.z();

        double minX = Math.min(x1, x2) - 1.5;
        double maxX = Math.max(x1, x2) + 1.5;
        double minZ = Math.min(z1, z2) - 1.5;
        double maxZ = Math.max(z1, z2) + 1.5;

        int sx = mapCoord(x1, minX, maxX, GRID_SIZE);
        int sz = mapCoord(z1, minZ, maxZ, GRID_SIZE);
        int ex = mapCoord(x2, minX, maxX, GRID_SIZE);
        int ez = mapCoord(z2, minZ, maxZ, GRID_SIZE);

        drawTrajectory(grid, sx, sz, ex, ez);

        grid[sz][sx] = 'S';
        grid[ez][ex] = 'X';

        double yStart = from.y();
        double yEnd = to.y();
        double minY = Math.min(yStart, yEnd) - 1.0;
        double maxY = Math.max(yStart, yEnd) + 1.0;

        output.add("&8+-----------------+  " + com.example.argusac.i18n.I18n.get(sender, "command.replay.height-bar"));
        for (int r = 0; r < GRID_SIZE; r++) {
            StringBuilder rowBuilder = new StringBuilder("&8|");
            for (int c = 0; c < GRID_SIZE; c++) {
                char ch = grid[r][c];
                if (ch == 'S') rowBuilder.append("&aS");
                else if (ch == 'X') rowBuilder.append("&cX");
                else if (ch == '*') rowBuilder.append("&e*");
                else rowBuilder.append("&8·");
            }
            rowBuilder.append("&8|  ");

            double rowYRatio = 1.0 - ((double) r / (GRID_SIZE - 1));
            int densityIdx = (int) Math.round(rowYRatio * (DENSITY_MAP.length - 1));
            char densityChar = DENSITY_MAP[Math.max(0, Math.min(densityIdx, DENSITY_MAP.length - 1))];
            rowBuilder.append("&b").append(densityChar).append(" &7Y: ").append(String.format("%.1f", minY + (maxY - minY) * rowYRatio));

            output.add(rowBuilder.toString());
        }
        output.add("&8+-----------------+");
        output.add(com.example.argusac.i18n.I18n.get(sender, "command.replay.legend"));

        return output;
    }

    private int mapCoord(double val, double min, double max, int size) {
        if (Math.abs(max - min) < 1e-4) return size / 2;
        double ratio = (val - min) / (max - min);
        int mapped = (int) Math.round(ratio * (size - 1));
        return Math.max(0, Math.min(mapped, size - 1));
    }

    private void drawTrajectory(char[][] grid, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            grid[y0][x0] = '*';
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }
}

