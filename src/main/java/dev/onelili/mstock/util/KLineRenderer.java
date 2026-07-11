package dev.onelili.mstock.util;

import dev.onelili.mstock.model.KLinePoint;

import java.util.ArrayList;
import java.util.List;

public class KLineRenderer {

    private static final int HEIGHT = 6;
    private static final int MAX_CANDLES = 20;
    // MiniMessage color tags for rising (red in CN stock convention) and falling (green)
    private static final String COLOR_UP = "<color:#FF5555>";
    private static final String COLOR_DOWN = "<color:#55FF55>";
    private static final String COLOR_RESET = "</color>";
    private static final String COLOR_WICK = "<gray>";
    private static final String COLOR_WICK_RESET = "</gray>";

    /**
     * Renders a list of KLinePoints into HEIGHT lines of MiniMessage text.
     * Each candle occupies 2 chars (body + space), max 20 candles in 40 chars.
     * Returns an empty list if points is null or empty.
     */
    public static List<String> render(List<KLinePoint> points) {
        if (points == null || points.isEmpty()) return List.of();

        // Sample down to MAX_CANDLES if needed
        List<KLinePoint> sampled = sample(points, MAX_CANDLES);
        int n = sampled.size();

        // Find global price range
        double globalMax = sampled.stream().mapToDouble(KLinePoint::getHigh).max().orElse(1);
        double globalMin = sampled.stream().mapToDouble(KLinePoint::getLow).min().orElse(0);
        double range = globalMax - globalMin;
        if (range == 0) range = 1;

        // For each candle pre-compute row ranges (0=bottom, HEIGHT-1=top)
        int[] bodyTop = new int[n];
        int[] bodyBot = new int[n];
        int[] wickTop = new int[n];
        int[] wickBot = new int[n];
        boolean[] isUp = new boolean[n];

        for (int i = 0; i < n; i++) {
            KLinePoint p = sampled.get(i);
            isUp[i] = p.getClose() >= p.getOpen();
            bodyTop[i] = toRow(Math.max(p.getOpen(), p.getClose()), globalMin, range);
            bodyBot[i] = toRow(Math.min(p.getOpen(), p.getClose()), globalMin, range);
            wickTop[i] = toRow(p.getHigh(), globalMin, range);
            wickBot[i] = toRow(p.getLow(), globalMin, range);
        }

        List<String> lines = new ArrayList<>(HEIGHT);
        // Build from top row (HEIGHT-1) down to row 0
        for (int row = HEIGHT - 1; row >= 0; row--) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < n; i++) {
                char ch = ' ';
                String colorOpen = "";
                String colorClose = "";

                if (row >= bodyBot[i] && row <= bodyTop[i]) {
                    // body
                    ch = '█';
                    colorOpen = isUp[i] ? COLOR_UP : COLOR_DOWN;
                    colorClose = COLOR_RESET;
                } else if (row >= wickBot[i] && row <= wickTop[i]) {
                    // wick
                    ch = '│';
                    colorOpen = COLOR_WICK;
                    colorClose = COLOR_WICK_RESET;
                }

                if (ch != ' ') {
                    sb.append(colorOpen).append(ch).append(colorClose);
                } else {
                    sb.append(' ');
                }
                sb.append(' '); // spacing between candles
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** Maps a price value to a row index in [0, HEIGHT-1]. */
    private static int toRow(double price, double min, double range) {
        double normalized = (price - min) / range;
        int row = (int) Math.round(normalized * (HEIGHT - 1));
        return Math.max(0, Math.min(HEIGHT - 1, row));
    }

    /** Uniformly samples the list down to at most maxCount elements. */
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
