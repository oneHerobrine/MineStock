package dev.onelili.mstock.recommend;

import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.stockio.StockApiService;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Provides one stable selection per calendar day and fresh quotes for that selection. */
public final class RecommendationService implements AutoCloseable {
    private final MainConfig config;
    private final StockApiService api;
    private final Logger logger;
    private final AutoRecommendationProvider autoProvider;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalDate selectionDate;
    private CompletableFuture<List<String>> selectedCodes;

    public RecommendationService(MainConfig config, StockApiService api, Logger logger) {
        this(config, api, logger, new AutoRecommendationProvider());
    }

    RecommendationService(MainConfig config, StockApiService api, Logger logger,
                          AutoRecommendationProvider autoProvider) {
        this.config = config;
        this.api = api;
        this.logger = logger;
        this.autoProvider = autoProvider;
    }

    public CompletableFuture<List<StockInfo>> getRecommendations() {
        return dailyCodes().thenCompose(codes -> {
            List<CompletableFuture<StockInfo>> requests = codes.stream()
                    .map(code -> api.fetch(code).exceptionally(error -> {
                        logger.warning("[MineStock] 获取推荐股票行情失败 " + code + ": "
                                + rootMessage(error));
                        return null;
                    }))
                    .toList();
            return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> requests.stream()
                            .map(request -> request.getNow(null))
                            .filter(Objects::nonNull)
                            .toList());
        });
    }

    public CompletableFuture<StockInfo> getRecommendation(int oneBasedIndex) {
        if (oneBasedIndex < 1) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Recommendation index must be at least 1"));
        }
        return dailyCodes().thenCompose(codes -> {
            if (oneBasedIndex > codes.size()) {
                return CompletableFuture.failedFuture(new IndexOutOfBoundsException(
                        "Only " + codes.size() + " recommendations are available"));
            }
            return api.fetch(codes.get(oneBasedIndex - 1));
        });
    }

    public synchronized void invalidate() {
        selectionDate = null;
        selectedCodes = null;
    }

    private synchronized CompletableFuture<List<String>> dailyCodes() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Recommendation service is closed"));
        }
        LocalDate today = LocalDate.now();
        if (today.equals(selectionDate) && selectedCodes != null) return selectedCodes;

        selectionDate = today;
        CompletableFuture<List<String>> created = config.isRecommendedPoolAuto()
                ? selectAutomatically()
                : selectFromConfiguredPool();
        CompletableFuture<List<String>> cached = created.thenApply(codes -> {
            if (codes.isEmpty()) throw new IllegalStateException("No recommendation is available");
            return List.copyOf(codes);
        });
        selectedCodes = cached;
        cached.whenComplete((ignored, error) -> {
            if (error == null) return;
            synchronized (RecommendationService.this) {
                if (selectedCodes == cached) {
                    selectionDate = null;
                    selectedCodes = null;
                }
            }
        });
        return cached;
    }

    private CompletableFuture<List<String>> selectAutomatically() {
        return autoProvider.fetchRanked(config.getAutoCandidateCount(), config.getRecommendedCount())
                .thenApply(stocks -> stocks.stream().map(StockInfo::getCode).toList());
    }

    private CompletableFuture<List<String>> selectFromConfiguredPool() {
        List<String> pool = new LinkedHashSet<>(config.getRecommendedPool().stream()
                .filter(Objects::nonNull)
                .map(code -> code.strip().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isBlank() && api.isSupported(code))
                .toList()).stream().toList();
        if (pool.isEmpty()) return CompletableFuture.completedFuture(List.of());

        List<CompletableFuture<StockInfo>> requests = pool.stream()
                .map(code -> api.fetch(code).exceptionally(error -> null))
                .toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> requests.stream()
                        .map(request -> request.getNow(null))
                        .filter(Objects::nonNull)
                        .sorted((a, b) -> {
                            int byChange = Double.compare(b.getChangePercent(), a.getChangePercent());
                            return byChange != 0 ? byChange : a.getCode().compareTo(b.getCode());
                        })
                        .limit(config.getRecommendedCount())
                        .map(StockInfo::getCode)
                        .toList());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (this) {
            if (selectedCodes != null) selectedCodes.cancel(true);
            selectedCodes = null;
            selectionDate = null;
        }
        autoProvider.close();
    }
}
