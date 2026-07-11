package dev.onelili.mstock.model;

public class KLinePoint {
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final String date;

    public KLinePoint(double open, double high, double low, double close, String date) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.date = date;
    }

    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public String getDate() { return date; }
}
