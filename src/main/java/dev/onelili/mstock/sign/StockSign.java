package dev.onelili.mstock.sign;

import org.bukkit.Location;
import org.bukkit.block.sign.Side;

import java.util.UUID;

public record StockSign(UUID worldId, int x, int y, int z, Mode mode, String argument, Side side) {
    public enum Mode { RECOMMEND, CODE }

    public static StockSign recommend(Location location, int index, Side side) {
        return at(location, Mode.RECOMMEND, Integer.toString(index), side);
    }

    public static StockSign code(Location location, String code, Side side) {
        return at(location, Mode.CODE, code, side);
    }

    private static StockSign at(Location location, Mode mode, String argument, Side side) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Location has no world");
        return new StockSign(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), mode, argument, side);
    }

    public String storageKey() {
        return worldId + "_" + x + "_" + y + "_" + z;
    }

    public boolean isAt(Location location) {
        return location.getWorld() != null
                && worldId.equals(location.getWorld().getUID())
                && x == location.getBlockX() && y == location.getBlockY() && z == location.getBlockZ();
    }
}
