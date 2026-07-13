package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when a player requests real-time stock data via a command.
 * Cancel this event to silently block the query (no API request will be made).
 */
public class StockQueryEvent extends AbstractStockEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private boolean cancelled;

    public StockQueryEvent(Player player, String stockCode) {
        super(player, stockCode);
    }

    @Override public boolean isCancelled()            { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()           { return HANDLERS; }
}
