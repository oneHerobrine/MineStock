package dev.onelili.mstock.model;

import java.util.UUID;

public class Holding {
    private final long id;
    private final UUID playerUuid;
    private final String stockCode;
    private int amount;
    private double avgCost;
    private double lastPrice;
    private long lastFetched;

    public Holding(long id, UUID playerUuid, String stockCode, int amount, double avgCost, double lastPrice, long lastFetched) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.stockCode = stockCode;
        this.amount = amount;
        this.avgCost = avgCost;
        this.lastPrice = lastPrice;
        this.lastFetched = lastFetched;
    }

    public long getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getStockCode() { return stockCode; }
    public int getAmount() { return amount; }
    public double getAvgCost() { return avgCost; }
    public double getLastPrice() { return lastPrice; }
    public long getLastFetched() { return lastFetched; }

    public void setAmount(int amount) { this.amount = amount; }
    public void setAvgCost(double avgCost) { this.avgCost = avgCost; }
    public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }
    public void setLastFetched(long lastFetched) { this.lastFetched = lastFetched; }
}
