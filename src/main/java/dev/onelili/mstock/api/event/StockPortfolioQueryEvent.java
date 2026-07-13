package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when a player requests to view their portfolio.
 * Cancel this event to silently block the portfolio display.
 * This event does not extend {@link AbstractStockEvent} because it is not stock-specific.
 */
public class StockPortfolioQueryEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private boolean cancelled;

    public StockPortfolioQueryEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() { return player; }

    @Override public boolean isCancelled()            { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()           { return HANDLERS; }
}
