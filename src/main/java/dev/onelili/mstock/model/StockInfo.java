package dev.onelili.mstock.model;

public class StockInfo {
    private final String code;
    private final String name;
    private final double price;
    private final double changeAmount;
    private final double changePercent;
    private final long fetchedAt;

    public StockInfo(String code, String name, double price, double changeAmount, double changePercent) {
        this.code = code;
        this.name = name;
        this.price = price;
        this.changeAmount = changeAmount;
        this.changePercent = changePercent;
        this.fetchedAt = System.currentTimeMillis();
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public double getChangeAmount() { return changeAmount; }
    public double getChangePercent() { return changePercent; }
    public long getFetchedAt() { return fetchedAt; }
}
