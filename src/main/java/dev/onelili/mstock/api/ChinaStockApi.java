package dev.onelili.mstock.api;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ChinaStockApi implements StockSource {

    private static final Pattern CHINA_CODE = Pattern.compile("^\\d{6}$");
    private final HttpClient http;
    private final Logger logger;
    private final List<ApiEntry> entries;

    static final class RateLimitException extends RuntimeException {
        RateLimitException(String msg) { super(msg); }
    }

    static final class InterfaceSkipException extends RuntimeException {
        InterfaceSkipException(String msg) { super(msg); }
    }

    public ChinaStockApi(Logger logger, List<ApiEntry> entries) {
        this.logger = logger;
        this.entries = entries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return CHINA_CODE.matcher(code).matches();
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        return fetchSequential(code, 0);
    }

    private CompletableFuture<StockInfo> fetchSequential(String code, int startIndex) {
        if (startIndex >= entries.size()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("所有 A 股 API 端点均失败，代码: " + code));
        }
        ApiEntry entry = entries.get(startIndex);
        return fetchOne(entry, code)
                .thenApply(info -> (StockInfo) info)
                .exceptionally(ex -> { throw sneakyThrow(ex); })
                .handle((info, ex) -> {
                    if (ex == null) return CompletableFuture.completedFuture(info);
                    Throwable cause = unwrap(ex);
                    if (cause instanceof RateLimitException) {
                        // 429：找下一条同 iface
                        int next = nextSameIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            logger.warning("[MineStock] A股 " + entry.iface() + " 所有 key 均触发频率限制，本次请求失败: " + code);
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] A股 " + entry.iface() + " 429，切换下一个同类 key: " + code);
                        return fetchSequential(code, next);
                    } else {
                        // 其他错误：跳过所有同 iface，换下一种
                        int next = nextDifferentIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] A股 " + entry.iface() + " 失败，切换 " + entries.get(next).iface() + ": " + code);
                        return fetchSequential(code, next);
                    }
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<StockInfo> fetchOne(ApiEntry entry, String code) {
        return switch (entry.iface()) {
            case "sina"       -> fetchSina(code);
            case "tencent"    -> fetchTencent(code);
            case "eastmoney"  -> fetchEastmoney(code);
            case "netease"    -> fetchNetease(code);
            default -> CompletableFuture.failedFuture(
                    new InterfaceSkipException("未知 A 股 interface: " + entry.iface()));
        };
    }

    // ── Sina ────────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchSina(String code) {
        String prefix = sinaPrefix(code);
        String url = "https://hq.sinajs.cn/list=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Sina 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Sina HTTP " + resp.statusCode());
                    return parseSina(code, resp.body());
                });
    }

    private StockInfo parseSina(String code, String body) {
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start)
            throw new InterfaceSkipException("Sina 空响应: " + body.substring(0, Math.min(100, body.length())));
        String[] fields = body.substring(start + 1, end).split(",");
        if (fields.length < 7)
            throw new InterfaceSkipException("Sina 字段不足: " + fields.length);
        String name = fields[0];
        double prevClose = parseDouble(fields[2]);
        double price = parseDouble(fields[3]);
        double changeAmount = price - prevClose;
        double changePercent = prevClose != 0 ? changeAmount / prevClose * 100.0 : 0.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    // ── Tencent ─────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchTencent(String code) {
        String prefix = sinaPrefix(code); // 腾讯和新浪前缀规则相同
        String url = "https://qt.gtimg.cn/q=" + prefix + code;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.qq.com/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Tencent 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Tencent HTTP " + resp.statusCode());
                    return parseTencent(code, resp.body());
                });
    }

    private StockInfo parseTencent(String code, String body) {
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start)
            throw new InterfaceSkipException("Tencent 空响应");
        String[] fields = body.substring(start + 1, end).split("~");
        if (fields.length < 47)
            throw new InterfaceSkipException("Tencent 字段不足: " + fields.length);
        String name = fields[1];
        double price = parseDouble(fields[3]);
        double prevClose = parseDouble(fields[4]);
        double changeAmount = parseDouble(fields[31]);
        double changePercent = parseDouble(fields[32]);
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    // ── Eastmoney ────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchEastmoney(String code) {
        String secid = eastmoneySecid(code);
        // f43=现价(×100存储), f57=股票名称, f170=涨跌幅(×100), f171=涨跌额(×100)
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
                    if (resp.statusCode() == 429) throw new RateLimitException("Eastmoney 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Eastmoney HTTP " + resp.statusCode());
                    return parseEastmoney(code, resp.body());
                });
    }

    private StockInfo parseEastmoney(String code, String body) {
        // {"rc":0,"rt":...,"data":{"f43":1045,"f57":"平安银行","f170":-38,"f171":-4},...}
        String dataBlock = extractBetween(body, "\"data\":{", "}");
        if (dataBlock == null)
            throw new InterfaceSkipException("Eastmoney 无 data 字段: " + body.substring(0, Math.min(200, body.length())));
        // f43 是价格 ×100
        double price = extractDouble(dataBlock, "f43") / 100.0;
        if (price == 0)
            throw new InterfaceSkipException("Eastmoney 价格为 0");
        String name = extractString(dataBlock, "f57");
        if (name == null || name.isEmpty()) name = code;
        double changePercent = extractDouble(dataBlock, "f170") / 100.0;
        double changeAmount = extractDouble(dataBlock, "f171") / 100.0;
        return new StockInfo(code, name, price, changeAmount, changePercent);
    }

    // ── Netease ──────────────────────────────────────────────────────────────

    private CompletableFuture<StockInfo> fetchNetease(String code) {
        // 网易财经代码格式：0开头=深圳，1开头=上海
        String prefix = code.startsWith("6") ? "1" : "0";
        String url = "https://api.money.126.net/data/feed/" + prefix + code + ",money.api.getStockBasicInfo";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://money.163.com/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Netease 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Netease HTTP " + resp.statusCode());
                    return parseNetease(code, resp.body());
                });
    }

    private StockInfo parseNetease(String code, String body) {
        // JSONP: _ntes_quote_callback({"0000001":{"code":"000001","name":"平安银行","price":10.45,"updown":-0.04,"percent":-0.38,...}});
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start)
            throw new InterfaceSkipException("Netease 空响应");
        // 找到股票数据对象（第二层 {}）
        int innerStart = body.indexOf('{', start + 1);
        int innerEnd = body.lastIndexOf('}', end - 1);
        if (innerStart < 0 || innerEnd <= innerStart)
            throw new InterfaceSkipException("Netease 数据格式异常");
        String obj = body.substring(innerStart, innerEnd + 1);
        String name = extractString(obj, "name");
        if (name == null || name.isEmpty()) name = code;
        double price = extractDouble(obj, "price");
        if (price == 0) throw new InterfaceSkipException("Netease 价格为 0");
        double changeAmount = extractDouble(obj, "updown");
        double changePercent = extractDouble(obj, "percent");
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
                    new RuntimeException("所有 A 股端点 K 线均失败，代码: " + code));
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
                        logger.warning("[MineStock] A股K线 " + entry.iface() + " 失败，切换 " + entries.get(next).iface() + ": " + code);
                        return fetchKLineSequential(code, days, next);
                    }
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<List<KLinePoint>> fetchKLineOne(ApiEntry entry, String code, int days) {
        return switch (entry.iface()) {
            case "sina"      -> fetchSinaKLine(code, days);
            case "eastmoney" -> fetchEastmoneyKLine(code, days);
            // 腾讯和网易 K 线不支持，跳过
            default -> CompletableFuture.failedFuture(
                    new InterfaceSkipException(entry.iface() + " 不支持 K 线"));
        };
    }

    private CompletableFuture<List<KLinePoint>> fetchSinaKLine(String code, int days) {
        String prefix = sinaPrefix(code);
        String url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php"
                + "/CN_MarketData.getKLineData?symbol=" + prefix + code
                + "&scale=240&datalen=" + days + "&ma=no";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.sina.com.cn/")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) throw new RateLimitException("Sina KLine 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Sina KLine HTTP " + resp.statusCode());
                    return parseSinaKLine(resp.body());
                });
    }

    private List<KLinePoint> parseSinaKLine(String body) {
        List<KLinePoint> result = new ArrayList<>();
        String trimmed = body.trim();
        if (!trimmed.startsWith("["))
            throw new InterfaceSkipException("Sina KLine 格式异常: " + trimmed.substring(0, Math.min(100, trimmed.length())));
        int i = 0;
        while (i < trimmed.length()) {
            int objStart = trimmed.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = trimmed.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = trimmed.substring(objStart, objEnd + 1);
            String date  = extractString(obj, "day");
            double open  = extractDouble(obj, "open");
            double high  = extractDouble(obj, "high");
            double low   = extractDouble(obj, "low");
            double close = extractDouble(obj, "close");
            if (date != null && close != 0) {
                result.add(new KLinePoint(open, high, low, close, date));
            }
            i = objEnd + 1;
        }
        if (result.isEmpty())
            throw new InterfaceSkipException("Sina KLine 空结果");
        return result;
    }

    private CompletableFuture<List<KLinePoint>> fetchEastmoneyKLine(String code, int days) {
        String secid = eastmoneySecid(code);
        // klt=101 日K, fqt=1 前复权, lmt=条数限制, fields2=日期,开,收,高,低
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
                    if (resp.statusCode() == 429) throw new RateLimitException("Eastmoney KLine 429");
                    if (resp.statusCode() != 200) throw new InterfaceSkipException("Eastmoney KLine HTTP " + resp.statusCode());
                    return parseEastmoneyKLine(resp.body());
                });
    }

    private List<KLinePoint> parseEastmoneyKLine(String body) {
        // {"rc":0,...,"data":{"klines":["2024-01-02,9.50,9.80,9.90,9.40,123456,...",...]},...}
        List<KLinePoint> result = new ArrayList<>();
        int klinesStart = body.indexOf("\"klines\":[");
        if (klinesStart < 0)
            throw new InterfaceSkipException("Eastmoney KLine 无 klines 字段");
        klinesStart += "\"klines\":[".length();
        int klinesEnd = body.indexOf(']', klinesStart);
        if (klinesEnd < 0)
            throw new InterfaceSkipException("Eastmoney KLine 格式异常");
        String klines = body.substring(klinesStart, klinesEnd);
        for (String segment : klines.split("\"")) {
            // 每个 K 线条目格式："date,open,close,high,low,volume,..."
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
            throw new InterfaceSkipException("Eastmoney KLine 空结果");
        return result;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private String sinaPrefix(String code) {
        return code.startsWith("6") ? "sh" : "sz";
    }

    private String eastmoneySecid(String code) {
        String mkt = code.startsWith("6") ? "1" : "0";
        return mkt + "." + code;
    }

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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static Throwable unwrap(Throwable ex) {
        return (ex.getCause() != null) ? ex.getCause() : ex;
    }
}
