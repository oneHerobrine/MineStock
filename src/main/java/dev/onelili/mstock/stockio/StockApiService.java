package dev.onelili.mstock.stockio;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class StockApiService implements AutoCloseable {
    private final List<StockSource> sources = new ArrayList<>();
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    // K-line cache: key = "CODE:days", value = cached result + timestamp
    private final Map<String, KLineCacheEntry> klineCache = new ConcurrentHashMap<>();
    private long klineCacheMs = 300_000L; // 5 minutes default

    public StockApiService(MainConfig config, Logger logger) {
        this.logger = logger;
        sources.add(new ChinaStockApi(logger));
        sources.add(new HkStockApi(logger));

        List<String> finnhubKeys    = new ArrayList<>();
        List<String> twelveDataKeys = new ArrayList<>();

        for (Map<?, ?> entry : config.getUsStockApis()) {
            String name = String.valueOf(entry.get("name"));
            List<String> keys = resolveKeys(entry);
            if ("finnhub".equalsIgnoreCase(name))       finnhubKeys.addAll(keys);
            else if ("twelvedata".equalsIgnoreCase(name)) twelveDataKeys.addAll(keys);
        }
        sources.add(new UsStockApi(logger, finnhubKeys, twelveDataKeys));

        long cfgCacheMs = config.getKlineCacheMs();
        if (cfgCacheMs > 0) klineCacheMs = cfgCacheMs;
    }

    StockApiService(List<StockSource> sources, long klineCacheMs, Logger logger) {
        this.logger = logger;
        this.sources.addAll(sources);
        if (klineCacheMs > 0) this.klineCacheMs = klineCacheMs;
    }

    /**
     * Reads API keys from a config entry.
     * Supports both the new list form (apikeys: [...]) and the legacy single-key form (apikey: "...").
     */
    private static List<String> resolveKeys(Map<?, ?> entry) {
        Object listVal = entry.get("apikeys");
        if (listVal instanceof List<?> rawList) {
            List<String> keys = new ArrayList<>();
            for (Object o : rawList) {
                String k = o != null ? String.valueOf(o).strip() : "";
                if (!k.isBlank()) keys.add(k);
            }
            if (!keys.isEmpty()) return keys;
        }
        // Fall back to legacy single apikey
        Object single = entry.get("apikey");
        String k = single != null ? String.valueOf(single).strip() : "";
        return k.isBlank() ? List.of() : List.of(k);
    }

    public CompletableFuture<StockInfo> fetch(String code) {
        if (closed.get()) return closedFuture();
        for (StockSource source : sources) {
            if (source.supports(code)) return track(() -> source.fetch(code));
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code: " + code));
    }

    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        if (closed.get()) return closedFuture();
        String cacheKey = code + ":" + days;
        KLineCacheEntry cached = klineCache.get(cacheKey);
        if (cached != null && !cached.isExpired(klineCacheMs)) {
            return CompletableFuture.completedFuture(cached.data);
        }
        for (StockSource source : sources) {
            if (source.supports(code)) {
                return track(() -> source.fetchKLine(code, days).thenApply(data -> {
                    klineCache.put(cacheKey, new KLineCacheEntry(data));
                    return data;
                }));
            }
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code for K-line: " + code));
    }

    public boolean isSupported(String code) {
        if (closed.get()) return false;
        return sources.stream().anyMatch(s -> s.supports(code));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        for (CompletableFuture<?> future : List.copyOf(inFlight)) future.cancel(true);
        inFlight.clear();
        klineCache.clear();
        for (StockSource source : sources) {
            try {
                source.close();
            } catch (RuntimeException e) {
                logger.warning("[MineStock] 关闭行情数据源失败: " + e.getMessage());
            }
        }
    }

    private <T> CompletableFuture<T> track(Supplier<CompletableFuture<T>> operation) {
        if (closed.get()) return closedFuture();

        CompletableFuture<T> future;
        try {
            future = operation.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        inFlight.add(future);
        future.whenComplete((ignored, error) -> inFlight.remove(future));
        if (closed.get() && inFlight.remove(future)) future.cancel(true);
        return future;
    }

    private static <T> CompletableFuture<T> closedFuture() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("MineStock stock service is closed"));
    }

    private static class KLineCacheEntry {
        final List<KLinePoint> data;
        final long timestamp;

        KLineCacheEntry(List<KLinePoint> data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}
