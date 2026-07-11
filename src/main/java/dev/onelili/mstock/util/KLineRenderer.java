package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders K-line close-price data as a continuous line chart for Minecraft chat (MiniMessage).
 *
 * Layout: 2*n-1 columns total.
 *   Even columns (0,2,4,...): data point — '_' when same row as neighbour(s), or row marker for '|' fill
 *   Odd  columns (1,3,5,...): connector between adjacent points
 *     diff == 0 : '_' at shared row
 *     diff == 1 : '/' or '\' at the transition row
 *     diff >= 2 : '/' or '\' at the ends + '|' filling the intermediate rows
 *
 * The point character on its own column is always '_', wrapped in a hover showing OHLC detail.
 * Color: rising = red (#FF5555), falling/flat = green (#55FF55).
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

        // grid[row][col]: the character to draw (' ' = empty)
        // colorUp[row][col]: true = red (rising), false = green (falling/flat)
        // hoverText[col]: non-null only for point columns (even cols), contains MiniMessage hover text
        int cols = 2 * n - 1;
        char[][]    grid     = new char[HEIGHT][cols];
        boolean[][] colorUp  = new boolean[HEIGHT][cols];
        String[]    hover    = new String[cols];

        for (int r = 0; r < HEIGHT; r++)
            for (int c = 0; c < cols; c++)
                grid[r][c] = ' ';

        // --- Place point markers (even columns) ---
        for (int i = 0; i < n; i++) {
            int col = i * 2;
            int row = rows[i];
            grid[row][col] = '_';
            // Determine color: compare with previous point (or next if first)
            boolean up = false;
            if (i > 0)          up = rows[i] > rows[i - 1];
            else if (n > 1)     up = rows[0] < rows[1]; // first point: look ahead (reversed: rising means next is higher)
            colorUp[row][col] = up;

            // Build hover text for this point
            KLinePoint p = sampled.get(i);
            hover[col] = buildHover(p);
        }

        // --- Place connectors (odd columns) ---
        for (int i = 0; i < n - 1; i++) {
            int r0  = rows[i];
            int r1  = rows[i + 1];
            int col = i * 2 + 1; // connector column
            boolean up = r1 > r0;
            boolean flat = r1 == r0;

            if (flat) {
                // Same row: draw '_' at that row
                grid[r0][col]    = '_';
                colorUp[r0][col] = false;
            } else {
                int diff = Math.abs(r1 - r0);
                int lo = Math.min(r0, r1);
                int hi = Math.max(r0, r1);

                if (diff == 1) {
                    // Single transition character: '/' sits at lo row, '\' sits at hi row
                    // Actually: '/' visually goes from lo-left to hi-right → draw at lo
                    //           '\' visually goes from hi-left to lo-right → draw at hi
                    if (up) {
                        // going up: left point is lower (r0=lo), right point is higher (r1=hi)
                        grid[lo][col]    = '/';
                        colorUp[lo][col] = true;
                    } else {
                        // going down: left point is higher (r0=hi), right point is lower (r1=lo)
                        grid[hi][col]    = '\\';
                        colorUp[hi][col] = false;
                    }
                } else {
                    // diff >= 2: fill intermediate rows with '|', cap with '/' or '\'
                    // Fill rows lo+1 .. hi-1 with '|'
                    for (int r = lo + 1; r <= hi - 1; r++) {
                        grid[r][col]    = '|';
                        colorUp[r][col] = up;
                    }
                    // Cap characters at the endpoints
                    if (up) {
                        // bottom cap: '/' at lo (connecting from left-low to right-high)
                        grid[lo][col]    = '/';
                        colorUp[lo][col] = true;
                        // top cap: '/' at hi as well (or omit — the | already reaches hi-1,
                        // and the point '_' at hi covers the top)
                        // Replace bottom of | with '/' and top of | with '/' too for clarity:
                        grid[hi][col]    = '/';
                        colorUp[hi][col] = true;
                    } else {
                        grid[hi][col]    = '\\';
                        colorUp[hi][col] = false;
                        grid[lo][col]    = '\\';
                        colorUp[lo][col] = false;
                    }
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
                    String hoverOpen  = hover[col] != null ? hover[col] : "";
                    String hoverClose = hover[col] != null ? "</hover>" : "";

                    if (!color.equals(currentColor)) {
                        sb.append(color);
                        currentColor = color;
                    }
                    sb.append(hoverOpen).append(ch).append(hoverClose);
                }
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static String buildHover(KLinePoint p) {
        return "<hover:show_text:'<gray>" + p.getDate()
                + "\n<white>开: " + fmt(p.getOpen())
                + " 高: " + fmt(p.getHigh())
                + "\n<white>低: " + fmt(p.getLow())
                + " 收: " + fmt(p.getClose())
                + "</white></gray>'>";
    }

    private static String fmt(double v) {
        // Strip trailing zeros but keep at least 2 decimal places
        String s = String.format("%.2f", v);
        return s;
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
