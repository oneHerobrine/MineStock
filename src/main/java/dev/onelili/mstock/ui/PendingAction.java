package dev.onelili.mstock.ui;

import java.util.UUID;

public class PendingAction {
    public enum Type { BUY, SELL, KLINE_CUSTOM }

    private final Type type;
    private final String stockCode;
    private final long timestamp;

    public PendingAction(Type type, String stockCode) {
        this.type = type;
        this.stockCode = stockCode;
        this.timestamp = System.currentTimeMillis();
    }

    public Type getType() { return type; }
    public String getStockCode() { return stockCode; }
    public long getTimestamp() { return timestamp; }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }
}
