package dev.onelili.mstock.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Base class for all MineStock events that involve a player and a stock code. */
public abstract class AbstractStockEvent extends Event {

    protected final Player player;
    protected final String stockCode;

    protected AbstractStockEvent(Player player, String stockCode) {
        this.player = player;
        this.stockCode = stockCode;
    }

    public Player getPlayer() { return player; }
    public String getStockCode() { return stockCode; }

    // Subclasses each declare their own HANDLERS + override getHandlerList().
    @Override
    public abstract @NotNull HandlerList getHandlers();
}
