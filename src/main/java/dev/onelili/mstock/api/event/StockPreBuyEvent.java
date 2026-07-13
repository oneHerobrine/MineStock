package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread before a player's buy order is executed.
 * Cancel this event to prevent the transaction from occurring.
 *
 * <p>All price values already include the configured {@code price-ratio}.
 */
public class StockPreBuyEvent extends AbstractStockEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int amount;
    private final double unitPrice;
    private final double fee;
    private final double totalCost;
    private boolean cancelled;

    /**
     * @param player    the buying player
     * @param stockCode the stock code (upper-case)
     * @param amount    number of shares to buy
     * @param unitPrice in-game unit price (real price × price-ratio)
     * @param fee       transaction fee
     * @param totalCost subtotal + fee
     */
    public StockPreBuyEvent(Player player, String stockCode, int amount,
                            double unitPrice, double fee, double totalCost) {
        super(player, stockCode);
        this.amount    = amount;
        this.unitPrice = unitPrice;
        this.fee       = fee;
        this.totalCost = totalCost;
    }

    public int getAmount()      { return amount; }
    public double getUnitPrice() { return unitPrice; }
    public double getFee()       { return fee; }
    public double getTotalCost() { return totalCost; }

    @Override public boolean isCancelled()            { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers()           { return HANDLERS; }
    public static HandlerList getHandlerList()                    { return HANDLERS; }
}
