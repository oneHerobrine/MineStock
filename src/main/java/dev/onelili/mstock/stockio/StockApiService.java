package dev.onelili.mstock.stockio;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class StockApiService {
    private final List<StockSource> sources = new ArrayList<>();
    private final Logger logger;

    // K-line cache: key = "CODE:days", value = cached result + timestamp
    private final Map<String, KLineCacheEntry> klineCache = new HashMap<>();
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
        for (StockSource source : sources) {
            if (source.supports(code)) return source.fetch(code);
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code: " + code));
    }

    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        String cacheKey = code + ":" + days;
        KLineCacheEntry cached = klineCache.get(cacheKey);
        if (cached != null && !cached.isExpired(klineCacheMs)) {
            return CompletableFuture.completedFuture(cached.data);
        }
        for (StockSource source : sources) {
            if (source.supports(code)) {
                return source.fetchKLine(code, days).thenApply(data -> {
                    klineCache.put(cacheKey, new KLineCacheEntry(data));
                    return data;
                });
            }
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code for K-line: " + code));
    }

    public boolean isSupported(String code) {
        return sources.stream().anyMatch(s -> s.supports(code));
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
