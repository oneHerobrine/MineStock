package dev.onelili.mstock.sign;

import org.bukkit.block.sign.Side;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StockSignTest {
    @Test
    void storageKeyIncludesWorldAndCoordinates() {
        UUID world = UUID.randomUUID();
        StockSign first = new StockSign(world, -10, 64, 25,
                StockSign.Mode.RECOMMEND, "1", Side.FRONT);
        StockSign other = new StockSign(world, -10, 64, 26,
                StockSign.Mode.CODE, "600519", Side.FRONT);

        assertEquals(world + "_-10_64_25", first.storageKey());
        assertNotEquals(first.storageKey(), other.storageKey());
    }
}
