package dev.onelili.mstock.api;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.StockInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class StockApiService {
    private final List<StockSource> sources = new ArrayList<>();
    private final Logger logger;

    public StockApiService(MainConfig config, Logger logger) {
        this.logger = logger;
        sources.add(new ChinaStockApi(logger));

        String finnhubKey = "";
        String twelveDataKey = "";
        for (Map<?, ?> entry : config.getUsStockApis()) {
            String name = String.valueOf(entry.get("name"));
            Object rawKey = entry.get("apikey");
            String key = rawKey != null ? String.valueOf(rawKey) : "";
            if ("finnhub".equalsIgnoreCase(name)) finnhubKey = key;
            else if ("twelvedata".equalsIgnoreCase(name)) twelveDataKey = key;
        }
        sources.add(new UsStockApi(logger, finnhubKey, twelveDataKey));
    }

    public CompletableFuture<StockInfo> fetch(String code) {
        for (StockSource source : sources) {
            if (source.supports(code)) return source.fetch(code);
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported stock code: " + code));
    }

    public boolean isSupported(String code) {
        return sources.stream().anyMatch(s -> s.supports(code));
    }
}
