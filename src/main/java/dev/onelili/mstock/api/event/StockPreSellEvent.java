package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread before a player's sell order is executed.
 * Cancel this event to prevent the transaction from occurring.
 *
 * <p>All price values already include the configured {@code price-ratio}.
 */
public class StockPreSellEvent extends AbstractStockEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int amount;
    private final double unitPrice;
    private final double fee;
    private final double income;
    private boolean cancelled;

    /**
     * @param player    the selling player
     * @param stockCode the stock code (upper-case)
     * @param amount    number of shares to sell
     * @param unitPrice in-game unit price at the time of sale
     * @param fee       transaction fee deducted from the proceeds
     * @param income    net proceeds after fee (subtotal - fee)
     */
    public StockPreSellEvent(Player player, String stockCode, int amount,
                             double unitPrice, double fee, double income) {
        super(player, stockCode);
        this.amount    = amount;
        this.unitPrice = unitPrice;
        this.fee       = fee;
        this.income    = income;
    }

    public int getAmount()       { return amount; }
    public double getUnitPrice() { return unitPrice; }
    public double getFee()       { return fee; }
    public double getIncome()    { return income; }

    @Override public boolean isCancelled()            { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()           { return HANDLERS; }
}
