package dev.onelili.mstock.api;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        return fetchFinnhubKLine(code.toUpperCase(), days)
                .thenApply(list -> {
                    if (list != null && !list.isEmpty()) return list;
                    throw new RuntimeException("finnhub kline returned empty");
                })
                .exceptionally(ex -> null)
                .thenCompose(list -> {
                    if (list != null) return CompletableFuture.completedFuture(list);
                    logger.warning("[MineStock] Finnhub KLine 失败，切换 Twelve Data: " + code);
                    return fetchTwelveDataKLine(code.toUpperCase(), days);
                });
    }

    private CompletableFuture<List<KLinePoint>> fetchFinnhubKLine(String code, int days) {
        if (finnhubKey == null || finnhubKey.isBlank()) {
            return CompletableFuture.failedFuture(new RuntimeException("Finnhub key not configured"));
        }
        long toTs = Instant.now().getEpochSecond();
        long fromTs = LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = "https://finnhub.io/api/v1/stock/candles?symbol=" + code
                + "&resolution=D&from=" + fromTs + "&to=" + toTs + "&token=" + finnhubKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200)
                        throw new RuntimeException("Finnhub KLine HTTP " + resp.statusCode());
                    return parseFinnhubKLine(resp.body());
                });
    }

    /**
     * Parses Finnhub candles response.
     * Format: {"c":[...],"h":[...],"l":[...],"o":[...],"t":[...],"s":"ok"}
     */
    private List<KLinePoint> parseFinnhubKLine(String body) {
        if (!body.contains("\"s\":\"ok\""))
            throw new RuntimeException("Finnhub KLine status not ok: " + body.substring(0, Math.min(200, body.length())));
        double[] c = extractDoubleArray(body, "c");
        double[] h = extractDoubleArray(body, "h");
        double[] l = extractDoubleArray(body, "l");
        double[] o = extractDoubleArray(body, "o");
        long[]   t = extractLongArray(body, "t");
        int len = c.length;
        if (len == 0) throw new RuntimeException("Finnhub KLine empty arrays");
        List<KLinePoint> result = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String date = t.length > i ? String.valueOf(t[i]) : String.valueOf(i);
            result.add(new KLinePoint(o[i], h[i], l[i], c[i], date));
        }
        return result;
    }

    private CompletableFuture<List<KLinePoint>> fetchTwelveDataKLine(String code, int days) {
        if (twelveDataKey == null || twelveDataKey.isBlank()) {
            return CompletableFuture.failedFuture(new RuntimeException("TwelveData key not configured"));
        }
        String url = "https://api.twelvedata.com/time_series?symbol=" + code
                + "&interval=1day&outputsize=" + days + "&apikey=" + twelveDataKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200)
                        throw new RuntimeException("TwelveData KLine HTTP " + resp.statusCode());
                    return parseTwelveDataKLine(resp.body());
                });
    }

    /**
     * Parses Twelve Data time_series response.
     * Format: {"values":[{"datetime":"2024-01-02","open":"...","high":"...","low":"...","close":"..."},...],...}
     * Note: Twelve Data returns newest first, so we reverse for chronological order.
     */
    private List<KLinePoint> parseTwelveDataKLine(String body) {
        List<KLinePoint> result = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int objStart = body.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = body.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = body.substring(objStart, objEnd + 1);
            String date = extractString(obj, "datetime");
            if (date != null) {
                double open  = extractDouble(obj, "open");
                double high  = extractDouble(obj, "high");
                double low   = extractDouble(obj, "low");
                double close = extractDouble(obj, "close");
                if (open != 0 || close != 0) {
                    result.add(new KLinePoint(open, high, low, close, date));
                }
            }
            i = objEnd + 1;
        }
        if (result.isEmpty())
            throw new RuntimeException("TwelveData KLine empty: " + body.substring(0, Math.min(200, body.length())));
        // Reverse so oldest data is first (chronological order for chart)
        java.util.Collections.reverse(result);
        return result;
    }

    /** Extracts a JSON numeric array like "c":[1.0,2.0,...] */
    private double[] extractDoubleArray(String json, String field) {
        String key = "\"" + field + "\":[";
        int s = json.indexOf(key);
        if (s < 0) return new double[0];
        s += key.length();
        int e = json.indexOf(']', s);
        if (e < 0) return new double[0];
        String[] parts = json.substring(s, e).split(",");
        double[] arr = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { arr[i] = Double.parseDouble(parts[i].trim()); } catch (Exception ex) { arr[i] = 0; }
        }
        return arr;
    }

    private long[] extractLongArray(String json, String field) {
        String key = "\"" + field + "\":[";
        int s = json.indexOf(key);
        if (s < 0) return new long[0];
        s += key.length();
        int e = json.indexOf(']', s);
        if (e < 0) return new long[0];
        String[] parts = json.substring(s, e).split(",");
        long[] arr = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { arr[i] = Long.parseLong(parts[i].trim()); } catch (Exception ex) { arr[i] = 0; }
        }
        return arr;
    }
}
