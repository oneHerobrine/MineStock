package dev.onelili.mstock.api;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ChinaStockApi implements StockSource {

    private static final Pattern CHINA_CODE = Pattern.compile("^\\d{6}$");
    private final HttpClient http;
    private final Logger logger;

    public ChinaStockApi(Logger logger) {
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return CHINA_CODE.matcher(code).matches();
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        // 新浪财经，失败后备用腾讯财经
        return fetchSina(code)
                .thenApply(info -> {
                    if (info != null) return info;
                    throw new RuntimeException("sina returned null");
                })
                .exceptionally(ex -> null)
                .thenCompose(info -> {
                    if (info != null) return CompletableFuture.completedFuture(info);
                    logger.warning("[MineStock] 新浪财经失败，切换腾讯财经: " + code);
                    return fetchTencent(code);
                });
    }

    private CompletableFuture<StockInfo> fetchSina(String code) {
        String prefix = code.startsWith("6") ? "sh" : "sz";
        String url = "https://hq.sinajs.cn/list=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseSina(code, resp.body());
                });
    }

    private StockInfo parseSina(String code, String body) {
        // var hq_str_sz000001="平安银行,10.50,10.49,10.45,10.51,10.40,10.44,10.45,..."
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) throw new RuntimeException("Sina empty response: " + body.substring(0, Math.min(100, body.length())));
        String[] fields = body.substring(start + 1, end).split(",");
        if (fields.length < 7) throw new RuntimeException("Sina fields too few: " + fields.length);
        String name = fields[0];
        double prevClose = parseDouble(fields[2]);
        double price = parseDouble(fields[3]);
        double changeAmount = price - prevClose;
        double changePercent = prevClose != 0 ? changeAmount / prevClose * 100.0 : 0.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private CompletableFuture<StockInfo> fetchTencent(String code) {
        String prefix = code.startsWith("6") ? "sh" : "sz";
        String url = "https://qt.gtimg.cn/q=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return parseTencent(code, resp.body());
                });
    }

    private StockInfo parseTencent(String code, String body) {
        // v_sz000001="51~平安银行~000001~10.45~10.49~10.50~...~-0.04~-0.38~..."
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) throw new RuntimeException("Tencent empty response");
        String[] fields = body.substring(start + 1, end).split("~");
        if (fields.length < 47) throw new RuntimeException("Tencent fields too few: " + fields.length);
        String name = fields[1];
        double price = parseDouble(fields[3]);
        double prevClose = parseDouble(fields[4]);
        double changeAmount = parseDouble(fields[31]);
        double changePercent = parseDouble(fields[32]);
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        // eastmoney: secid = "1.code" for SH (6开头), "0.code" for SZ
        String market = code.startsWith("6") ? "1" : "0";
        String beg = LocalDate.now().minusDays(days)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                + "?secid=" + market + "." + code
                + "&fields1=f1,f2,f3,f4,f5&fields2=f51,f52,f53,f54,f55"
                + "&klt=101&fqt=0&beg=" + beg + "&end=20500101";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://quote.eastmoney.com/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200)
                        throw new RuntimeException("EastMoney KLine HTTP " + resp.statusCode());
                    return parseEastMoneyKLine(resp.body());
                });
    }

    /**
     * Parses EastMoney kline response.
     * klines array entries: "2024-01-02,open,close,high,low"
     */
    private List<KLinePoint> parseEastMoneyKLine(String body) {
        List<KLinePoint> result = new ArrayList<>();
        // Find "klines":["...","..."]
        String marker = "\"klines\":[";
        int start = body.indexOf(marker);
        if (start < 0) {
            // data may be null when no trading data (e.g. new stock)
            if (body.contains("\"klines\":null") || body.contains("\"data\":null"))
                throw new RuntimeException("EastMoney KLine: no data");
            throw new RuntimeException("EastMoney KLine: unexpected format: "
                    + body.substring(0, Math.min(200, body.length())));
        }
        start += marker.length();
        int end = body.indexOf(']', start);
        if (end < 0) throw new RuntimeException("EastMoney KLine: malformed array");

        String arr = body.substring(start, end);
        // Each entry is "YYYY-MM-DD,open,close,high,low" wrapped in quotes
        int pos = 0;
        while (pos < arr.length()) {
            int q1 = arr.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = arr.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String entry = arr.substring(q1 + 1, q2);
            String[] f = entry.split(",");
            if (f.length >= 5) {
                double open  = parseDouble(f[1]);
                double close = parseDouble(f[2]);
                double high  = parseDouble(f[3]);
                double low   = parseDouble(f[4]);
                result.add(new KLinePoint(open, high, low, close, f[0]));
            }
            pos = q2 + 1;
        }
        if (result.isEmpty())
            throw new RuntimeException("EastMoney KLine empty result");
        return result;
    }
}
