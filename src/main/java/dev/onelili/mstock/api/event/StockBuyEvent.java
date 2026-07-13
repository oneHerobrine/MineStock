package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread after a player's buy order has been persisted to the database
 * and the economy has been charged. This event is informational and cannot be cancelled.
 *
 * <p>All price values already include the configured {@code price-ratio}.
 */
public class StockBuyEvent extends AbstractStockEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int amount;
    private final double unitPrice;
    private final double fee;
    private final double totalCost;

    public StockBuyEvent(Player player, String stockCode, int amount,
                         double unitPrice, double fee, double totalCost) {
        super(player, stockCode);
        this.amount    = amount;
        this.unitPrice = unitPrice;
        this.fee       = fee;
        this.totalCost = totalCost;
    }

    public int getAmount()       { return amount; }
    public double getUnitPrice() { return unitPrice; }
    public double getFee()       { return fee; }
    public double getTotalCost() { return totalCost; }

    @Override public @NotNull HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()           { return HANDLERS; }
}
