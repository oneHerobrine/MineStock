package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders K-line close-price data as a continuous line chart for Minecraft chat (MiniMessage).
 *
 * Column layout: 2*n-1 columns total.
 *   Even columns (0,2,4,...): data point, always '_' at the point's row.
 *   Odd  columns (1,3,5,...): connector between adjacent points i and i+1.
 *
 * Connector rules (r0 = row of point i, r1 = row of point i+1):
 *   diff == 0          → '_' at r0 (flat)
 *   diff == 1, up      → '/' at r0  (same row as left '_', connects up-right to next '_')
 *   diff == 1, down    → '\' at r0  (same row as left '_', connects down-right to next '_')
 *   diff >= 2, up      → '/' at r0, '|' at r0+1..r1-1, nothing at r1 (right point's '_' caps it)
 *   diff >= 2, down    → '\' at r0, '|' at r1+1..r0-1, nothing at r1 (right point's '_' caps it)
 *
 * Hover: every non-space cell carries a hover tag.
 *   Point column  → hover shows that point's OHLC.
 *   Connector column → hover shows the RIGHT point's (i+1) OHLC.
 *
 * Color: UP (red #FF5555) when r1 > r0, DOWN (green #55FF55) otherwise.
 */
public class KLineRenderer {

    private static final int HEIGHT     = 6;
    private static final int MAX_POINTS = 20;

    private static final String UP   = "<color:#FF5555>";
    private static final String DOWN = "<color:#55FF55>";

    public static List<String> render(List<KLinePoint> points) {
        if (points == null || points.isEmpty()) return List.of();

        List<KLinePoint> sampled = sample(points, MAX_POINTS);
        int n = sampled.size();

        double globalMax = sampled.stream().mapToDouble(KLinePoint::getClose).max().orElse(1);
        double globalMin = sampled.stream().mapToDouble(KLinePoint::getClose).min().orElse(0);
        double range = globalMax - globalMin;
        if (range == 0) range = 1;

        int[] rows = new int[n];
        for (int i = 0; i < n; i++) {
            rows[i] = toRow(sampled.get(i).getClose(), globalMin, range);
        }

        int cols = (n == 1) ? 1 : 2 * n - 1;

        // Per-cell: character, color (true=up/red), hover text (null = no hover)
        char[][]    grid      = new char[HEIGHT][cols];
        boolean[][] colorUp   = new boolean[HEIGHT][cols];
        String[][]  cellHover = new String[HEIGHT][cols];

        for (int r = 0; r < HEIGHT; r++)
            for (int c = 0; c < cols; c++)
                grid[r][c] = ' ';

        // --- Place data point markers (even columns) ---
        for (int i = 0; i < n; i++) {
            int col = i * 2;
            int row = rows[i];
            grid[row][col] = '_';

            // Color: direction toward the next point (or from prev if last)
            boolean up;
            if (i < n - 1)      up = rows[i + 1] > rows[i];
            else if (n > 1)     up = rows[i] > rows[i - 1];
            else                up = false;
            colorUp[row][col]   = up;
            cellHover[row][col] = buildHover(sampled.get(i));
        }

        // --- Place connectors (odd columns) ---
        for (int i = 0; i < n - 1; i++) {
            int r0  = rows[i];
            int r1  = rows[i + 1];
            int col = i * 2 + 1;
            boolean up   = r1 > r0;
            boolean flat = r1 == r0;
            String  hov  = buildHover(sampled.get(i + 1)); // connector shows right-point data

            if (flat) {
                place(grid, colorUp, cellHover, r0, col, '_', false, hov);
            } else {
                int diff = Math.abs(r1 - r0);
                if (diff == 1) {
                    // '/' or '\' at r0 (left point's row), no fill needed
                    place(grid, colorUp, cellHover, r0, col, up ? '/' : '\\', up, hov);
                } else {
                    // diff >= 2
                    // Slope char at r0 (left point's row)
                    place(grid, colorUp, cellHover, r0, col, up ? '/' : '\\', up, hov);
                    // '|' fill between r0 and r1 (exclusive of both endpoints)
                    int fillLo = Math.min(r0, r1) + 1;
                    int fillHi = Math.max(r0, r1) - 1;
                    for (int r = fillLo; r <= fillHi; r++) {
                        place(grid, colorUp, cellHover, r, col, '|', up, hov);
                    }
                    // r1 row left empty — right point's '_' naturally caps the connector
                }
            }
        }

        // --- Render rows top to bottom ---
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
                    String color = colorUp[row][col] ? UP : DOWN;
                    if (!color.equals(currentColor)) {
                        sb.append(color);
                        currentColor = color;
                    }
                    String hov = cellHover[row][col];
                    if (hov != null) sb.append(hov);
                    sb.append(ch);
                    if (hov != null) sb.append("</hover>");
                }
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static void place(char[][] grid, boolean[][] colorUp, String[][] cellHover,
                               int row, int col, char ch, boolean up, String hov) {
        grid[row][col]      = ch;
        colorUp[row][col]   = up;
        cellHover[row][col] = hov;
    }

    private static String buildHover(KLinePoint p) {
        return "<hover:show_text:'<gray>" + p.getDate()
                + "<newline><white>开: " + fmt(p.getOpen())
                + "  高: " + fmt(p.getHigh())
                + "<newline>低: " + fmt(p.getLow())
                + "  收: " + fmt(p.getClose())
                + "</white></gray>'>";
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
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
