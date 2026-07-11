package dev.onelili.mstock.ui;

public class PendingAction {
    public enum Type { BUY, SELL }

    private final Type type;
    private final String stockCode;
    private final long timestamp;
    // Set after the player enters an amount; used during the confirm step.
    private int amount = 0;

    public PendingAction(Type type, String stockCode) {
        this.type = type;
        this.stockCode = stockCode;
        this.timestamp = System.currentTimeMillis();
    }

    public Type getType() { return type; }
    public String getStockCode() { return stockCode; }
    public long getTimestamp() { return timestamp; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    /** A pending action is in the confirm stage once an amount has been set. */
    public boolean isAwaitingConfirm() { return amount > 0; }

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - timestamp > timeoutMillis;
    }
}
