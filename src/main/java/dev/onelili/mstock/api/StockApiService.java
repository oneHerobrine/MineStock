package dev.onelili.mstock.api;

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

    private final Map<String, KLineCacheEntry> klineCache = new HashMap<>();
    private long klineCacheMs = 300_000L;

    public StockApiService(MainConfig config, Logger logger) {
        this.logger = logger;

        sources.add(new ChinaStockApi(logger, config.getApiEntries("cn-stock-apis")));
        sources.add(new HkStockApi(logger, config.getApiEntries("hk-stock-apis")));
        sources.add(new UsStockApi(logger, config.getApiEntries("us-stock-apis")));

        long cfgCacheMs = config.getKlineCacheMs();
        if (cfgCacheMs > 0) klineCacheMs = cfgCacheMs;
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
