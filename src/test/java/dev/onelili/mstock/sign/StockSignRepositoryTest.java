package dev.onelili.mstock.sign;

import org.bukkit.block.sign.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockSignRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsManagedSigns() {
        StockSign expected = new StockSign(UUID.randomUUID(), 12, 70, -8,
                StockSign.Mode.CODE, "600519", Side.BACK);
        StockSignRepository writer = new StockSignRepository(
                temporaryDirectory.toFile(), Logger.getAnonymousLogger());
        writer.put(expected);

        StockSignRepository reader = new StockSignRepository(
                temporaryDirectory.toFile(), Logger.getAnonymousLogger());

        assertEquals(1, reader.all().size());
        assertEquals(expected, reader.all().getFirst());
    }
}
