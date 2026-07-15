package dev.onelili.mstock.sign;

import dev.onelili.mstock.MineStock;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Locale;

public final class StockSignListener implements Listener {
    private final MineStock plugin;
    private final StockSignService signs;

    public StockSignListener(MineStock plugin, StockSignService signs) {
        this.plugin = plugin;
        this.signs = signs;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Location location = event.getBlock().getLocation();
        signs.removeEditedSide(location, event.getSide());
        String marker = ChatColor.stripColor(event.getLine(0));
        if (marker == null || !"[minestock]".equalsIgnoreCase(marker.strip())) return;

        if (!event.getPlayer().hasPermission("minestock.use")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "你没有创建 MineStock 告示牌的权限。");
            return;
        }

        String mode = clean(event.getLine(1)).toLowerCase(Locale.ROOT);
        String argument = clean(event.getLine(2));
        StockSign definition;
        if ("recommend".equals(mode)) {
            try {
                int index = Integer.parseInt(argument);
                if (index < 1) throw new NumberFormatException("index < 1");
                definition = StockSign.recommend(location, index, event.getSide());
            } catch (NumberFormatException error) {
                reject(event, "recommend 的第三行必须是大于或等于 1 的整数。");
                return;
            }
        } else if ("code".equals(mode)) {
            String code = argument.toUpperCase(Locale.ROOT);
            if (!plugin.getApi().isSupported(code)) {
                reject(event, "code 的第三行不是受支持的股票代码。");
                return;
            }
            definition = StockSign.code(location, code, event.getSide());
        } else {
            reject(event, "第二行只能填写 recommend 或 code。");
            return;
        }

        event.setLine(0, ChatColor.GOLD + "[MineStock]");
        event.setLine(1, ChatColor.GRAY + "正在获取行情…");
        event.setLine(2, "");
        event.setLine(3, "");
        signs.register(definition);
        signs.refresh(definition);
        event.getPlayer().sendMessage(ChatColor.GREEN + "MineStock 行情告示牌创建成功。");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        removeAndValidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        removeAndValidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        signs.validateNearLater(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::removeAndValidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::removeAndValidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(this::removeAndValidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(this::removeAndValidate);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        signs.refreshChunk(event.getChunk());
    }

    private void removeAndValidate(Block block) {
        signs.remove(block.getLocation());
        signs.validateNearLater(block.getLocation());
    }

    private static void reject(SignChangeEvent event, String message) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + message);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String stripped = ChatColor.stripColor(value);
        return stripped != null ? stripped.strip() : "";
    }
}
