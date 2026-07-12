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
import java.util.regex.Pattern;

public class StockApiService {
    private final List<StockSource> sources = new ArrayList<>();
    private final CryptoApi cryptoApi;
    private final UsStockApi usStockApi;
    // US_CODE 用于并行竞速判断
    private static final Pattern US_ALPHA = Pattern.compile("^[A-Za-z]{1,10}$");
    private final Logger logger;

    private final Map<String, KLineCacheEntry> klineCache = new HashMap<>();
    private long klineCacheMs = 300_000L;

    public StockApiService(MainConfig config, Logger logger) {
        this.logger = logger;

        sources.add(new ChinaStockApi(logger, config.getApiEntries("cn-stock-apis")));
        sources.add(new HkStockApi(logger, config.getApiEntries("hk-stock-apis")));
        usStockApi = new UsStockApi(logger, config.getApiEntries("us-stock-apis"));
        sources.add(usStockApi);
        // 加密货币默认 tradeable：实时交易所 true，聚合源 false（由配置覆盖）
        cryptoApi = new CryptoApi(logger, config.getApiEntries("crypto-apis", true));

        long cfgCacheMs = config.getKlineCacheMs();
        if (cfgCacheMs > 0) klineCacheMs = cfgCacheMs;
    }

    public CompletableFuture<StockInfo> fetch(String code) {
        // 带后缀的加密货币代码（BTC/USDT 或 BTC-USD）直接路由到 CryptoApi
        if (cryptoApi.supports(code)) return cryptoApi.fetch(code);

        // 纯字母代码可能是美股也可能是加密货币（用户期望并行竞速）
        if (US_ALPHA.matcher(code).matches()) {
            return fetchUsOrCryptoRace(code);
        }

        // 其余走原有路由（A 股、港股）
        for (StockSource source : sources) {
            if (source.supports(code)) return source.fetch(code);
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code: " + code));
    }

    /**
     * 纯字母代码并行查询美股和加密货币（自动补 /USDT 后缀），先成功先返回。
     * 若两者均失败则返回美股的失败原因。
     */
    private CompletableFuture<StockInfo> fetchUsOrCryptoRace(String code) {
        CompletableFuture<StockInfo> usFuture = usStockApi.fetch(code);
        String cryptoCode = code.toUpperCase() + "/USDT";
        CompletableFuture<StockInfo> cryptoFuture = cryptoApi.fetch(cryptoCode);

        CompletableFuture<StockInfo> result = new CompletableFuture<>();
        usFuture.whenComplete((info, ex) -> {
            if (ex == null && !result.isDone()) result.complete(info);
        });
        cryptoFuture.whenComplete((info, ex) -> {
            if (ex == null && !result.isDone()) result.complete(info);
        });
        // 两者都完成后，若 result 仍未完成（意味着两者均失败），以美股错误为准
        CompletableFuture.allOf(
                usFuture.exceptionally(e -> null),
                cryptoFuture.exceptionally(e -> null)
        ).thenRun(() -> {
            if (!result.isDone()) {
                Throwable usEx = usFuture.isCompletedExceptionally()
                        ? usFuture.handle((v, e) -> e).join()
                        : new RuntimeException("所有 API 均失败，代码: " + code);
                result.completeExceptionally(usEx);
            }
        });
        return result;
    }

    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        String cacheKey = code + ":" + days;
        KLineCacheEntry cached = klineCache.get(cacheKey);
        if (cached != null && !cached.isExpired(klineCacheMs)) {
            return CompletableFuture.completedFuture(cached.data);
        }
        // K 线不做加密货币并行竞速，直接按原有路由
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
        if (cryptoApi.supports(code)) return true;
        if (US_ALPHA.matcher(code).matches()) return true; // 并行竞速模式
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
