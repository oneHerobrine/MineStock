package dev.onelili.mstock.api;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface StockSource {
    boolean supports(String code);
    CompletableFuture<StockInfo> fetch(String code);

    default CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("K-line not supported for: " + code));
    }
}
