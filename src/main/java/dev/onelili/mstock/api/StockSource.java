package dev.onelili.mstock.api;

import dev.onelili.mstock.model.StockInfo;

import java.util.concurrent.CompletableFuture;

public interface StockSource {
    boolean supports(String code);
    CompletableFuture<StockInfo> fetch(String code);
}
