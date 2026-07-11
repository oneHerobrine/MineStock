package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders K-line data as a slash/backslash/underscore line chart suitable for
 * Minecraft chat (MiniMessage format).
 *
 * Chart characters:
 *   /  rising segment
 *   \  falling segment
 *   _  flat segment — drawn one row ABOVE its logical row because the underscore
 *      glyph sits at the bottom of its cell and appears disconnected otherwise.
 *
 * Color: rising segments red (#FF5555), falling/flat segments green (#55FF55).
 * Each data point occupies 2 character columns (char + space), up to MAX_POINTS
 * points displayed. Excess points are uniformly sampled.
 */
public class KLineRenderer {

    private static final int HEIGHT = 5;      // visible rows in chart
    private static final int MAX_POINTS = 24; // max data points → 48 chars wide

    private static final String UP   = "<color:#FF5555>";
    private static final String DOWN = "<color:#55FF55>";

    /**
     * Returns HEIGHT lines of MiniMessage text representing the price trend.
     * Returns an empty list if points is null or empty.
     */
    public static List<String> render(List<KLinePoint> points) {
        if (points == null || points.isEmpty()) return List.of();

        List<KLinePoint> sampled = sample(points, MAX_POINTS);
        int n = sampled.size();

        // Map each close price to a row index: 0 = bottom, HEIGHT-1 = top
        double globalMax = sampled.stream().mapToDouble(KLinePoint::getClose).max().orElse(1);
        double globalMin = sampled.stream().mapToDouble(KLinePoint::getClose).min().orElse(0);
        double range = globalMax - globalMin;
        if (range == 0) range = 1;

        int[] rows = new int[n];
        for (int i = 0; i < n; i++) {
            rows[i] = toRow(sampled.get(i).getClose(), globalMin, range);
        }

        // Grid: grid[row][col], rows indexed 0..HEIGHT-1 (0=bottom, HEIGHT-1=top)
        // Each point uses 2 columns: the segment character + a space.
        // Between point i and i+1 we draw one segment character at column i*2.
        // We have (n-1) segments, so we need 2*(n-1)+1 columns minimum.
        // For a single point we still render one column.
        int cols = Math.max(1, 2 * n - 1);
        char[][]   grid  = new char[HEIGHT][cols];
        boolean[][] isUp = new boolean[HEIGHT][cols];
        for (int r = 0; r < HEIGHT; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = ' ';
            }
        }

        for (int i = 0; i < n - 1; i++) {
            int col = i * 2;          // segment at even column
            int r0  = rows[i];
            int r1  = rows[i + 1];
            boolean up = r1 > r0;
            boolean flat = r1 == r0;

            if (flat) {
                // Underscore displayed one row HIGHER to look connected
                int drawRow = Math.min(r0 + 1, HEIGHT - 1);
                grid[drawRow][col]  = '_';
                isUp[drawRow][col]  = false;
            } else if (up) {
                // '/' drawn at the starting (lower) row
                grid[r0][col] = '/';
                isUp[r0][col] = true;
            } else {
                // '\' drawn at the starting (higher) row
                grid[r0][col] = '\\';
                isUp[r0][col] = false;
            }
        }

        // Also mark the last point's column if it's the only point
        if (n == 1) {
            int drawRow = Math.min(rows[0] + 1, HEIGHT - 1);
            grid[drawRow][0] = '_';
        }

        // Build MiniMessage lines from top to bottom.
        // Only emit a color tag when the color changes; no closing tags needed.
        List<String> lines = new ArrayList<>(HEIGHT);
        for (int row = HEIGHT - 1; row >= 0; row--) {
            StringBuilder sb = new StringBuilder("  ");
            String currentColor = null;
            for (int col = 0; col < cols; col++) {
                char ch = grid[row][col];
                if (ch == ' ') {
                    currentColor = null;
                    sb.append(' ');
                } else {
                    String color = isUp[row][col] ? UP : DOWN;
                    if (!color.equals(currentColor)) {
                        sb.append(color);
                        currentColor = color;
                    }
                    sb.append(ch);
                }
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static int toRow(double price, double min, double range) {
        double normalized = (price - min) / range;
        int row = (int) Math.round(normalized * (HEIGHT - 1));
        return Math.max(0, Math.min(HEIGHT - 1, row));
    }

    private static List<KLinePoint> sample(List<KLinePoint> points, int maxCount) {
        if (points.size() <= maxCount) return points;
        List<KLinePoint> result = new ArrayList<>(maxCount);
        double step = (double) (points.size() - 1) / (maxCount - 1);
        for (int i = 0; i < maxCount; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }
}
