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

public class HkStockApi implements StockSource {

    // 纯数字 4-5 位（0700）或带 .HK 后缀（0700.HK），代码部分 4-5 位数字
    private static final Pattern HK_CODE = Pattern.compile("^(\\d{4,5})(\\.HK)?$", Pattern.CASE_INSENSITIVE);
    private final HttpClient http;
    private final Logger logger;
    private final List<ApiEntry> entries;

    private static final class RateLimitException extends RuntimeException {
        RateLimitException(String msg) { super(msg); }
    }

    private static final class InterfaceSkipException extends RuntimeException {
        InterfaceSkipException(String msg) { super(msg); }
    }

    public HkStockApi(Logger logger, List<ApiEntry> entries) {
        this.logger = logger;
        this.entries = entries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return HK_CODE.matcher(code).matches();
    }

    /** 从输入代码提取纯数字部分（去掉 .HK 后缀），并补齐为 5 位（eastmoney 要求） */
    private String normalizeCode(String code) {
        java.util.regex.Matcher m = HK_CODE.matcher(code);
        if (!m.matches()) return code;
        String digits = m.group(1);
        // eastmoney 港股代码通常 5 位，不足则左补 0
        while (digits.length() < 5) digits = "0" + digits;
        return digits;
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        return fetchSequential(code, 0);
    }

    private CompletableFuture<StockInfo> fetchSequential(String code, int startIndex) {
        if (startIndex >= entries.size()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("所有港股 API 端点均失败，代码: " + code));
        }
        ApiEntry entry = entries.get(startIndex);
        return fetchOne(entry, code)
                .handle((info, ex) -> {
                    if (ex == null) return CompletableFuture.completedFuture(info);
                    Throwable cause = unwrap(ex);
                    if (cause instanceof RateLimitException) {
                        int next = nextSameIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            logger.warning("[MineStock] 港股 " + entry.iface() + " 所有 key 均触发频率限制，本次请求失败: " + code);
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] 港股 " + entry.iface() + " 429，切换下一个同类 key: " + code);
                        return fetchSequential(code, next);
                    } else {
                        int next = nextDifferentIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] 港股 " + entry.iface() + " 失败，切换 " + entries.get(next).iface() + ": " + code);
                        return fetchSequential(code, next);
                    }
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<StockInfo> fetchOne(ApiEntry entry, String code) {
        return switch (entry.iface()) {
            case "eastmoney"  -> fetchEastmoney(code);
            case "finnhub"    -> {
                if (!entry.hasKey()) yield CompletableFuture.failedFuture(
                        new InterfaceSkipException("finnhub 未配置 apikey"));
                yield fetchFinnhub(entry.apiKey(), code);
            }
            case "twelvedata" -> {
                if (!entry.hasKey()) yield CompletableFuture.failedFuture(
                        new InterfaceSkipException("twelvedata 未配置 apikey"));
                yield fetchTwelveData(entry.apiKey(), code);
            }
            default -> CompletableFuture.failedFuture(
                    new InterfaceSkipException("未知港股 interface: " + entry.iface()));
        };
    }

    // ── Eastmoney ────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchEastmoney(String code) {
        String digits = normalizeCode(code);
        // 港股 secid 前缀：116
        String secid = "116." + digits;
        String url = "https://push2.eastmoney.com/api/qt/stock/get?secid=" + secid
                + "&fields=f43,f57,f170,f171&ut=fa5fd1943c7b386f172d6893dbfba10b";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.eastmoney.com/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Eastmoney HK 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Eastmoney HK HTTP " + resp.statusCode());
                    return parseEastmoney(code, resp.body());
                });
    }

    private StockInfo parseEastmoney(String code, String body) {
        String dataBlock = extractBetween(body, "\"data\":{", "}");
        if (dataBlock == null)
            throw new InterfaceSkipException("Eastmoney HK 无 data 字段: " + body.substring(0, Math.min(200, body.length())));
        double price = extractDouble(dataBlock, "f43") / 100.0;
        if (price == 0)
            throw new InterfaceSkipException("Eastmoney HK 价格为 0");
        String name = extractString(dataBlock, "f57");
        if (name == null || name.isEmpty()) name = code;
        double changePercent = extractDouble(dataBlock, "f170") / 100.0;
        double changeAmount  = extractDouble(dataBlock, "f171") / 100.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    // ── Finnhub ──────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchFinnhub(String key, String code) {
        // Finnhub 港股格式：0700.HK
        String digits = normalizeCode(code);
        // 去掉前导零拼接 .HK（Finnhub 通常不需要前导零）
        String symbol = digits.replaceFirst("^0+(?!$)", "") + ".HK";
        String url = "https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + key;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Finnhub HK 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Finnhub HK HTTP " + resp.statusCode());
                    String body = resp.body();
                    if (body.contains("\"error\"") && body.contains("limit"))
                        throw new RateLimitException("Finnhub HK rate limit in body");
                    return parseFinnhub(code, symbol, body);
                });
    }

    private StockInfo parseFinnhub(String code, String symbol, String body) {
        double price = extractDouble(body, "c");
        double changeAmount  = extractDouble(body, "d");
        double changePercent = extractDouble(body, "dp");
        if (price == 0)
            throw new InterfaceSkipException("Finnhub HK 价格为 0: " + body.substring(0, Math.min(200, body.length())));
        return new StockInfo(code, symbol, price, changeAmount, changePercent);
    }

    // ── Twelve Data ──────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchTwelveData(String key, String code) {
        // Twelve Data 港股格式：0700:HKEX
        String digits = normalizeCode(code);
        String symbol = digits + ":HKEX";
        String url = "https://api.twelvedata.com/quote?symbol=" + symbol + "&apikey=" + key;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("TwelveData HK 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("TwelveData HK HTTP " + resp.statusCode());
                    String body = resp.body();
                    if (body.contains("\"code\":429") || (body.contains("\"status\":\"error\"") && body.contains("limit")))
                        throw new RateLimitException("TwelveData HK rate limit in body");
                    return parseTwelveData(code, body);
                });
    }

    private StockInfo parseTwelveData(String code, String body) {
        String name = extractString(body, "name");
        if (name == null || name.isEmpty()) name = code;
        double price = extractDouble(body, "close");
        double changeAmount  = extractDouble(body, "change");
        double changePercent = extractDouble(body, "percent_change");
        if (price == 0)
            throw new InterfaceSkipException("TwelveData HK 价格为 0: " + body.substring(0, Math.min(200, body.length())));
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    // ── K-line ───────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        return fetchKLineSequential(code, days, 0);
    }

    private CompletableFuture<List<KLinePoint>> fetchKLineSequential(String code, int days, int startIndex) {
        if (startIndex >= entries.size()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("所有港股端点 K 线均失败，代码: " + code));
        }
        ApiEntry entry = entries.get(startIndex);
        return fetchKLineOne(entry, code, days)
                .handle((list, ex) -> {
                    if (ex == null) return CompletableFuture.completedFuture(list);
                    Throwable cause = unwrap(ex);
                    if (cause instanceof RateLimitException) {
                        int next = nextSameIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            return CompletableFuture.<List<KLinePoint>>failedFuture(cause);
                        }
                        return fetchKLineSequential(code, days, next);
                    } else {
                        int next = nextDifferentIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            return CompletableFuture.<List<KLinePoint>>failedFuture(cause);
                        }
                        logger.warning("[MineStock] 港股K线 " + entry.iface() + " 失败，切换 " + entries.get(next).iface() + ": " + code);
                        return fetchKLineSequential(code, days, next);
                    }
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<List<KLinePoint>> fetchKLineOne(ApiEntry entry, String code, int days) {
        return switch (entry.iface()) {
            case "eastmoney"  -> fetchEastmoneyKLine(code, days);
            case "finnhub"    -> {
                if (!entry.hasKey()) yield CompletableFuture.failedFuture(
                        new InterfaceSkipException("finnhub 未配置 apikey"));
                yield fetchFinnhubKLine(entry.apiKey(), code, days);
            }
            case "twelvedata" -> {
                if (!entry.hasKey()) yield CompletableFuture.failedFuture(
                        new InterfaceSkipException("twelvedata 未配置 apikey"));
                yield fetchTwelveDataKLine(entry.apiKey(), code, days);
            }
            default -> CompletableFuture.failedFuture(
                    new InterfaceSkipException(entry.iface() + " 不支持港股 K 线"));
        };
    }

    private CompletableFuture<List<KLinePoint>> fetchEastmoneyKLine(String code, int days) {
        String digits = normalizeCode(code);
        String secid = "116." + digits;
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + secid
                + "&klt=101&fqt=1&lmt=" + days
                + "&fields2=f51,f52,f53,f54,f55"
                + "&ut=fa5fd1943c7b386f172d6893dbfba10b";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.eastmoney.com/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Eastmoney HK KLine 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Eastmoney HK KLine HTTP " + resp.statusCode());
                    return parseEastmoneyKLine(resp.body());
                });
    }

    private List<KLinePoint> parseEastmoneyKLine(String body) {
        List<KLinePoint> result = new ArrayList<>();
        int klinesStart = body.indexOf("\"klines\":[");
        if (klinesStart < 0)
            throw new InterfaceSkipException("Eastmoney HK KLine 无 klines 字段");
        klinesStart += "\"klines\":[".length();
        int klinesEnd = body.indexOf(']', klinesStart);
        if (klinesEnd < 0)
            throw new InterfaceSkipException("Eastmoney HK KLine 格式异常");
        String klines = body.substring(klinesStart, klinesEnd);
        for (String segment : klines.split("\"")) {
            if (segment.isBlank() || segment.equals(",")) continue;
            String[] parts = segment.split(",");
            if (parts.length < 5) continue;
            String date  = parts[0].trim();
            double open  = parseDouble(parts[1]);
            double close = parseDouble(parts[2]);
            double high  = parseDouble(parts[3]);
            double low   = parseDouble(parts[4]);
            if (close != 0) result.add(new KLinePoint(open, high, low, close, date));
        }
        if (result.isEmpty())
            throw new InterfaceSkipException("Eastmoney HK KLine 空结果");
        return result;
    }

    private CompletableFuture<List<KLinePoint>> fetchFinnhubKLine(String key, String code, int days) {
        String digits = normalizeCode(code);
        String symbol = digits.replaceFirst("^0+(?!$)", "") + ".HK";
        long toTs   = Instant.now().getEpochSecond();
        long fromTs = LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = "https://finnhub.io/api/v1/stock/candles?symbol=" + symbol
                + "&resolution=D&from=" + fromTs + "&to=" + toTs + "&token=" + key;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Finnhub HK KLine 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Finnhub HK KLine HTTP " + resp.statusCode());
                    String body = resp.body();
                    if (body.contains("\"error\"") && body.contains("limit"))
                        throw new RateLimitException("Finnhub HK KLine rate limit in body");
                    return parseFinnhubKLine(body);
                });
    }

    private List<KLinePoint> parseFinnhubKLine(String body) {
        if (!body.contains("\"s\":\"ok\""))
            throw new InterfaceSkipException("Finnhub HK KLine status not ok: " + body.substring(0, Math.min(200, body.length())));
        double[] c = extractDoubleArray(body, "c");
        double[] h = extractDoubleArray(body, "h");
        double[] l = extractDoubleArray(body, "l");
        double[] o = extractDoubleArray(body, "o");
        long[]   t = extractLongArray(body, "t");
        int len = c.length;
        if (len == 0) throw new InterfaceSkipException("Finnhub HK KLine 空数组");
        List<KLinePoint> result = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String date = t.length > i ? String.valueOf(t[i]) : String.valueOf(i);
            result.add(new KLinePoint(o[i], h[i], l[i], c[i], date));
        }
        return result;
    }

    private CompletableFuture<List<KLinePoint>> fetchTwelveDataKLine(String key, String code, int days) {
        String digits = normalizeCode(code);
        String symbol = digits + ":HKEX";
        String url = "https://api.twelvedata.com/time_series?symbol=" + symbol
                + "&interval=1day&outputsize=" + days + "&apikey=" + key;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("TwelveData HK KLine 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("TwelveData HK KLine HTTP " + resp.statusCode());
                    String body = resp.body();
                    if (body.contains("\"code\":429") || (body.contains("\"status\":\"error\"") && body.contains("limit")))
                        throw new RateLimitException("TwelveData HK KLine rate limit in body");
                    return parseTwelveDataKLine(body);
                });
    }

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
            throw new InterfaceSkipException("TwelveData HK KLine 空结果: " + body.substring(0, Math.min(200, body.length())));
        java.util.Collections.reverse(result);
        return result;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private int nextSameIface(String iface, int fromIndex) {
        for (int i = fromIndex; i < entries.size(); i++) {
            if (entries.get(i).iface().equals(iface)) return i;
        }
        return -1;
    }

    private int nextDifferentIface(String iface, int fromIndex) {
        for (int i = fromIndex; i < entries.size(); i++) {
            if (!entries.get(i).iface().equals(iface)) return i;
        }
        return -1;
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    private double extractDouble(String json, String field) {
        String key = "\"" + field + "\":";
        int s = json.indexOf(key);
        if (s < 0) return 0.0;
        s += key.length();
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

    private String extractBetween(String text, String open, String close) {
        int s = text.indexOf(open);
        if (s < 0) return null;
        s += open.length();
        int e = text.indexOf(close, s);
        if (e < 0) return null;
        return text.substring(s, e);
    }

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

    private static Throwable unwrap(Throwable ex) {
        return (ex.getCause() != null) ? ex.getCause() : ex;
    }
}
