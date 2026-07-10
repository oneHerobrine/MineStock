package dev.onelili.mstock.api;

import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class UsStockApi implements StockSource {

    private static final Pattern US_CODE = Pattern.compile("^[A-Za-z]{1,10}$");
    private final HttpClient http;
    private final Logger logger;
    private final String finnhubKey;
    private final String twelveDataKey;

    public UsStockApi(Logger logger, String finnhubKey, String twelveDataKey) {
        this.logger = logger;
        this.finnhubKey = finnhubKey;
        this.twelveDataKey = twelveDataKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return US_CODE.matcher(code).matches();
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        return fetchFinnhub(code)
                .thenApply(info -> {
                    if (info != null) return info;
                    throw new RuntimeException("finnhub returned null");
                })
                .exceptionally(ex -> null)
                .thenCompose(info -> {
                    if (info != null) return CompletableFuture.completedFuture(info);
                    logger.warning("[MineStock] Finnhub 失败，切换 Twelve Data: " + code);
                    return fetchTwelveData(code);
                });
    }

    private CompletableFuture<StockInfo> fetchFinnhub(String code) {
        if (finnhubKey == null || finnhubKey.isBlank()) {
            return CompletableFuture.failedFuture(new RuntimeException("Finnhub key not configured"));
        }
        String url = "https://finnhub.io/api/v1/quote?symbol=" + code.toUpperCase() + "&token=" + finnhubKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("Finnhub HTTP " + resp.statusCode());
                    return parseFinnhub(code.toUpperCase(), resp.body());
                });
    }

    private StockInfo parseFinnhub(String code, String body) {
        // {"c":213.49,"d":2.88,"dp":1.3685,"h":213.74,"l":209.82,"o":210.61,"pc":210.61,"t":1752177601}
        double price = extractDouble(body, "c");
        double changeAmount = extractDouble(body, "d");
        double changePercent = extractDouble(body, "dp");
        if (price == 0) throw new RuntimeException("Finnhub price is 0: " + body.substring(0, Math.min(200, body.length())));
        return new StockInfo(code, code, price, changeAmount, changePercent);
    }

    private CompletableFuture<StockInfo> fetchTwelveData(String code) {
        if (twelveDataKey == null || twelveDataKey.isBlank()) {
            return CompletableFuture.failedFuture(new RuntimeException("TwelveData key not configured"));
        }
        String url = "https://api.twelvedata.com/quote?symbol=" + code.toUpperCase() + "&apikey=" + twelveDataKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("TwelveData HTTP " + resp.statusCode());
                    return parseTwelveData(code.toUpperCase(), resp.body());
                });
    }

    private StockInfo parseTwelveData(String code, String body) {
        // {"symbol":"AAPL","name":"Apple Inc","close":"213.49","percent_change":"1.36750","change":"2.88000",...}
        String name = extractString(body, "name");
        if (name == null || name.isEmpty()) name = code;
        double price = extractDouble(body, "close");
        double changeAmount = extractDouble(body, "change");
        double changePercent = extractDouble(body, "percent_change");
        if (price == 0) throw new RuntimeException("TwelveData price is 0: " + body.substring(0, Math.min(200, body.length())));
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private double extractDouble(String json, String field) {
        String key = "\"" + field + "\":";
        int s = json.indexOf(key);
        if (s < 0) return 0.0;
        s += key.length();
        // skip optional quotes
        if (s < json.length() && json.charAt(s) == '"') s++;
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-' || json.charAt(e) == '.')) e++;
        try { return Double.parseDouble(json.substring(s, e)); } catch (Exception ex) { return 0.0; }
    }

    private String extractString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int s = json.indexOf(key);
        if (s < 0) return null;
        s += key.length();
        int e = json.indexOf('"', s);
        if (e < 0) return null;
        return json.substring(s, e);
    }
}
