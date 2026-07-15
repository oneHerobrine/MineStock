package dev.onelili.mstock.sign;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.recommend.RecommendationService;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.stockio.StockApiService;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class StockSignService implements AutoCloseable {
    private final MineStock plugin;
    private final StockSignRepository repository;
    private final AtomicLong generation = new AtomicLong();
    private volatile StockApiService api;
    private volatile RecommendationService recommendations;
    private volatile MainConfig config;
    private CompatScheduler.ScheduledTaskHandle refreshTask;

    public StockSignService(MineStock plugin, StockApiService api,
                            RecommendationService recommendations, MainConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.recommendations = recommendations;
        this.config = config;
        this.repository = new StockSignRepository(plugin.getDataFolder(), plugin.getLogger());
    }

    public void start() {
        scheduleRefresh();
        CompatScheduler.get().runAsync(plugin, this::refreshAll);
    }

    public synchronized void reconfigure(StockApiService api,
                                         RecommendationService recommendations,
                                         MainConfig config) {
        generation.incrementAndGet();
        this.api = api;
        this.recommendations = recommendations;
        this.config = config;
        if (refreshTask != null) refreshTask.cancel();
        scheduleRefresh();
        refreshAll();
    }

    public void register(StockSign sign) {
        repository.put(sign);
    }

    public boolean remove(Location location) {
        return repository.remove(location);
    }

    public boolean removeEditedSide(Location location, Side side) {
        StockSign current = repository.find(location);
        return current != null && current.side() == side && repository.remove(location);
    }

    public boolean mayBeAffectedBy(Location location) {
        return repository.all().stream().anyMatch(sign -> location.getWorld() != null
                && sign.worldId().equals(location.getWorld().getUID())
                && Math.abs(sign.x() - location.getBlockX()) <= 1
                && Math.abs(sign.y() - location.getBlockY()) <= 1
                && Math.abs(sign.z() - location.getBlockZ()) <= 1);
    }

    public void validateNearLater(Location location) {
        if (!mayBeAffectedBy(location)) return;
        for (StockSign sign : repository.all()) {
            if (location.getWorld() == null || !sign.worldId().equals(location.getWorld().getUID())) continue;
            if (Math.abs(sign.x() - location.getBlockX()) > 1
                    || Math.abs(sign.y() - location.getBlockY()) > 1
                    || Math.abs(sign.z() - location.getBlockZ()) > 1) continue;
            World world = plugin.getServer().getWorld(sign.worldId());
            if (world == null) continue;
            Location signLocation = new Location(world, sign.x(), sign.y(), sign.z());
            CompatScheduler.get().runAtLocationLater(plugin, signLocation, () -> {
                if (!world.isChunkLoaded(sign.x() >> 4, sign.z() >> 4)) return;
                if (!(signLocation.getBlock().getState() instanceof Sign)) {
                    repository.remove(signLocation);
                }
            }, 2L);
        }
    }

    public void refresh(StockSign sign) {
        long currentGeneration = generation.get();
        request(sign).whenComplete((info, error) -> {
            if (generation.get() != currentGeneration) return;
            if (error == null && info != null) update(sign, info);
            else updateError(sign, error);
        });
    }

    public void refreshAll() {
        long currentGeneration = generation.get();
        List<StockSign> signs = repository.all();
        Map<String, CompletableFuture<StockInfo>> requests = new HashMap<>();
        for (StockSign sign : signs) {
            String key = sign.mode() + ":" + sign.argument();
            requests.computeIfAbsent(key, ignored -> request(sign));
        }
        for (StockSign sign : signs) {
            CompletableFuture<StockInfo> request = requests.get(sign.mode() + ":" + sign.argument());
            request.whenComplete((info, error) -> {
                if (generation.get() != currentGeneration) return;
                if (error == null && info != null) update(sign, info);
                else updateError(sign, error);
            });
        }
    }

    public void refreshChunk(Chunk chunk) {
        for (StockSign sign : repository.all()) {
            if (sign.worldId().equals(chunk.getWorld().getUID())
                    && sign.x() >> 4 == chunk.getX() && sign.z() >> 4 == chunk.getZ()) {
                refresh(sign);
            }
        }
    }

    private CompletableFuture<StockInfo> request(StockSign sign) {
        if (sign.mode() == StockSign.Mode.CODE) return api.fetch(sign.argument());
        try {
            return recommendations.getRecommendation(Integer.parseInt(sign.argument()));
        } catch (NumberFormatException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void update(StockSign definition, StockInfo info) {
        runAtSign(definition, sign -> {
            var side = sign.getSide(definition.side());
            side.setLine(0, ChatColor.GOLD + "[MineStock]");
            side.setLine(1, ChatColor.AQUA + info.getCode() + " " + ChatColor.WHITE
                    + shorten(info.getName(), 10));
            side.setLine(2, ChatColor.GRAY + "价格 " + ChatColor.WHITE
                    + format2(info.getPrice() * config.getPriceRatio()));
            ChatColor color = info.getChangePercent() >= 0 ? ChatColor.RED : ChatColor.GREEN;
            String prefix = info.getChangePercent() >= 0 ? "+" : "";
            side.setLine(3, ChatColor.GRAY + "涨跌 " + color + prefix
                    + format2(info.getChangePercent()) + "%");
            sign.update(true, false);
        });
    }

    private void updateError(StockSign definition, Throwable error) {
        String detail = errorText(definition, error);
        plugin.getLogger().warning("[MineStock] 刷新告示牌失败 " + definition.storageKey() + ": " + detail);
        runAtSign(definition, sign -> {
            var side = sign.getSide(definition.side());
            side.setLine(0, ChatColor.GOLD + "[MineStock]");
            side.setLine(1, ChatColor.RED + "行情获取失败");
            side.setLine(2, ChatColor.GRAY + shorten(detail, 14));
            side.setLine(3, ChatColor.DARK_GRAY + "稍后自动重试");
            sign.update(true, false);
        });
    }

    private void runAtSign(StockSign definition, java.util.function.Consumer<Sign> action) {
        World world = plugin.getServer().getWorld(definition.worldId());
        if (world == null || !world.isChunkLoaded(definition.x() >> 4, definition.z() >> 4)) return;
        Location location = new Location(world, definition.x(), definition.y(), definition.z());
        CompatScheduler.get().runAtLocation(plugin, location, () -> {
            StockSign current = repository.find(location);
            if (!definition.equals(current)) return;
            if (!(location.getBlock().getState() instanceof Sign sign)) {
                repository.remove(location);
                return;
            }
            action.accept(sign);
        });
    }

    private synchronized void scheduleRefresh() {
        refreshTask = CompatScheduler.get().runAsyncTimer(
                plugin, this::refreshAll, config.getSignRefreshTicks(), config.getSignRefreshTicks());
    }

    private static String shorten(String value, int maxCharacters) {
        if (value == null || value.isBlank()) return "-";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCharacters) return value;
        int end = value.offsetByCodePoints(0, Math.max(1, maxCharacters - 1));
        return value.substring(0, end) + "…";
    }

    private static String rootMessage(Throwable error) {
        if (error == null) return "未知错误";
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static String errorText(StockSign definition, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IndexOutOfBoundsException) {
                return "没有第 " + definition.argument() + " 支推荐";
            }
            current = current.getCause();
        }
        return rootMessage(error);
    }

    private static String format2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    public synchronized void close() {
        generation.incrementAndGet();
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }
}
