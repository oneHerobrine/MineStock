package dev.onelili.mstock.api;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加密货币行情 API 实现。
 *
 * 支持代码格式：
 *   BASE/QUOTE  例如 BTC/USDT、ETH/USDT
 *   BASE-QUOTE  例如 BTC-USD、ETH-USD
 *
 * 纯字母代码（如 BTC）由 StockApiService 做并行竞速，本类不单独匹配。
 *
 * 支持 interface：binance（无需 key）、okx（无需 key）、
 *                 kraken（无需 key）、coingecko（需 apikey）
 */
public class CryptoApi implements StockSource {

    // 匹配 BASE/QUOTE 或 BASE-QUOTE，BASE 1-10 字符，QUOTE 1-10 字符
    private static final Pattern CRYPTO_CODE =
            Pattern.compile("^([A-Za-z]{1,10})[/\\-]([A-Za-z]{1,10})$");

    private final HttpClient http;
    private final Logger logger;
    private final List<ApiEntry> entries;

    private static final class RateLimitException extends RuntimeException {
        RateLimitException(String msg) { super(msg); }
    }

    private static final class InterfaceSkipException extends RuntimeException {
        InterfaceSkipException(String msg) { super(msg); }
    }

    public CryptoApi(Logger logger, List<ApiEntry> entries) {
        this.logger = logger;
        this.entries = entries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supports(String code) {
        return CRYPTO_CODE.matcher(code).matches();
    }

    /**
     * 解析代码，返回 [base, quote]，例如 BTC/USDT → ["BTC","USDT"]
     */
    static String[] splitCode(String code) {
        Matcher m = CRYPTO_CODE.matcher(code);
        if (!m.matches()) throw new IllegalArgumentException("Not a crypto code: " + code);
        return new String[]{ m.group(1).toUpperCase(), m.group(2).toUpperCase() };
    }

    @Override
    public CompletableFuture<StockInfo> fetch(String code) {
        return fetchSequential(code, 0);
    }

    private CompletableFuture<StockInfo> fetchSequential(String code, int startIndex) {
        if (startIndex >= entries.size()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("所有加密货币 API 端点均失败，代码: " + code));
        }
        ApiEntry entry = entries.get(startIndex);
        return fetchOne(entry, code)
                .handle((info, ex) -> {
                    if (ex == null) return CompletableFuture.completedFuture(info);
                    Throwable cause = unwrap(ex);
                    if (cause instanceof RateLimitException) {
                        int next = nextSameIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            logger.warning("[MineStock] 加密货币 " + entry.iface() + " 所有 key 均触发频率限制: " + code);
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] 加密货币 " + entry.iface() + " 429，切换下一个同类 key: " + code);
                        return fetchSequential(code, next);
                    } else {
                        int next = nextDifferentIface(entry.iface(), startIndex + 1);
                        if (next < 0) {
                            return CompletableFuture.<StockInfo>failedFuture(cause);
                        }
                        logger.warning("[MineStock] 加密货币 " + entry.iface() + " 失败，切换 "
                                + entries.get(next).iface() + ": " + code);
                        return fetchSequential(code, next);
                    }
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<StockInfo> fetchOne(ApiEntry entry, String code) {
        return switch (entry.iface()) {
            case "binance"   -> fetchBinance(code);
            case "okx"       -> fetchOkx(code);
            case "kraken"    -> fetchKraken(code);
            case "coingecko" -> fetchCoinGecko(entry, code);
            default -> CompletableFuture.failedFuture(
                    new InterfaceSkipException("未知加密货币 interface: " + entry.iface()));
        };
    }

    // ── Binance ────────────────────────────────────────────────────────────────
    // 无需 key。GET /api/v3/ticker/24hr?symbol=BTCUSDT
    // 返回 {"symbol":"BTCUSDT","lastPrice":"67432.00","priceChange":"...",
    //        "priceChangePercent":"...","bidPrice":"...","askPrice":"..."}

    private CompletableFuture<StockInfo> fetchBinance(String code) {
        String[] parts = splitCode(code);
        String symbol = parts[0] + parts[1]; // BTCUSDT
        String url = "https://api.binance.com/api/v3/ticker/24hr?symbol=" + symbol;
        return sendGet(url).thenApply(resp -> {
            if (resp.statusCode() == 429 || resp.statusCode() == 418)
                throw new RateLimitException("Binance 429/418: " + code);
            if (resp.statusCode() != 200)
                throw new InterfaceSkipException("Binance HTTP " + resp.statusCode() + ": " + code);
            String body = resp.body();
            if (body.contains("\"code\":-1121") || body.contains("Invalid symbol"))
                throw new InterfaceSkipException("Binance 无效交易对: " + code);
            double price = extractDouble(body, "lastPrice");
            if (price == 0) throw new InterfaceSkipException("Binance 价格为 0: " + code);
            double changeAmount = extractDouble(body, "priceChange");
            double changePct = extractDouble(body, "priceChangePercent");
            String name = parts[0] + "/" + parts[1];
            return new StockInfo(code.toUpperCase(), name, price, changeAmount, changePct);
        });
    }

    // ── OKX ───────────────────────────────────────────────────────────────────
    // 无需 key。GET /api/v5/market/ticker?instId=BTC-USDT
    // 返回 {"code":"0","data":[{"instId":"BTC-USDT","last":"67432","open24h":"...",
    //        "sodUtc0":"..."}]}
    // changeAmount = last - sodUtc0（当日开盘价）

    private CompletableFuture<StockInfo> fetchOkx(String code) {
        String[] parts = splitCode(code);
        String instId = parts[0] + "-" + parts[1]; // BTC-USDT
        String url = "https://www.okx.com/api/v5/market/ticker?instId=" + instId;
        return sendGet(url).thenApply(resp -> {
            if (resp.statusCode() == 429)
                throw new RateLimitException("OKX 429: " + code);
            if (resp.statusCode() != 200)
                throw new InterfaceSkipException("OKX HTTP " + resp.statusCode() + ": " + code);
            String body = resp.body();
            if (!body.contains("\"code\":\"0\""))
                throw new InterfaceSkipException("OKX 错误响应: " + code);
            // data 数组里第一个对象
            int dataStart = body.indexOf("[{");
            if (dataStart < 0) throw new InterfaceSkipException("OKX 空 data: " + code);
            String obj = body.substring(dataStart + 1);
            double price = extractDouble(obj, "last");
            if (price == 0) throw new InterfaceSkipException("OKX 价格为 0: " + code);
            double open24h = extractDouble(obj, "open24h");
            double changeAmount = price - open24h;
            double changePct = open24h > 0 ? (changeAmount / open24h) * 100.0 : 0;
            String name = parts[0] + "/" + parts[1];
            return new StockInfo(code.toUpperCase(), name, price, changeAmount, changePct);
        });
    }

    // ── Kraken ────────────────────────────────────────────────────────────────
    // 无需 key。GET /0/public/Ticker?pair=XBTUSD 或 ETHUSD
    // BTC 在 Kraken 中为 XBT，USD 计价对为 ZUSD
    // 返回 {"result":{"XXBTZUSD":{"c":["67432.0","0.01"],"o":"67000.0",...}}}
    // 价格: result[key].c[0]，开盘: result[key].o

    private CompletableFuture<StockInfo> fetchKraken(String code) {
        String[] parts = splitCode(code);
        // Kraken 特殊符号映射
        String base = krakenSymbol(parts[0]);
        String quote = krakenSymbol(parts[1]);
        String pair = base + quote;
        String url = "https://api.kraken.com/0/public/Ticker?pair=" + pair;
        return sendGet(url).thenApply(resp -> {
            if (resp.statusCode() == 429)
                throw new RateLimitException("Kraken 429: " + code);
            if (resp.statusCode() != 200)
                throw new InterfaceSkipException("Kraken HTTP " + resp.statusCode() + ": " + code);
            String body = resp.body();
            if (body.contains("\"error\":[\"EQuery:Unknown asset pair\"]")
                    || body.contains("\"error\":[\"EQuery:Invalid asset pair\"]"))
                throw new InterfaceSkipException("Kraken 无效交易对: " + code);
            if (!body.contains("\"error\":[]"))
                throw new InterfaceSkipException("Kraken 错误: " + body.substring(0, Math.min(200, body.length())));
            // 找 "c":["price",...]
            double price = extractKrakenArrayFirst(body, "c");
            if (price == 0) throw new InterfaceSkipException("Kraken 价格为 0: " + code);
            double open = extractKrakenArrayFirst(body, "o");
            if (open == 0) {
                // "o" 在 Kraken 是字符串而非数组
                open = extractDouble(body, "o");
            }
            double changeAmount = price - open;
            double changePct = open > 0 ? (changeAmount / open) * 100.0 : 0;
            String name = parts[0] + "/" + parts[1];
            return new StockInfo(code.toUpperCase(), name, price, changeAmount, changePct);
        });
    }

    /** Kraken 交易对符号映射（BTC→XBT，USD→ZUSD 等） */
    private static String krakenSymbol(String s) {
        return switch (s.toUpperCase()) {
            case "BTC"  -> "XBT";
            case "USD"  -> "ZUSD";
            case "EUR"  -> "ZEUR";
            case "GBP"  -> "ZGBP";
            case "JPY"  -> "ZJPY";
            case "CAD"  -> "ZCAD";
            case "AUD"  -> "ZAUD";
            default     -> s.toUpperCase();
        };
    }

    // ── CoinGecko ─────────────────────────────────────────────────────────────
    // 需要免费 apikey。
    // GET /api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true
    // 代码 BASE 需要映射到 CoinGecko coin id（BTC→bitcoin, ETH→ethereum 等）
    // 只支持 USD/USDT/EUR/BTC 计价，quote 部分作为 vs_currency

    private CompletableFuture<StockInfo> fetchCoinGecko(ApiEntry entry, String code) {
        if (!entry.hasKey())
            return CompletableFuture.failedFuture(
                    new InterfaceSkipException("CoinGecko 未配置 apikey"));
        String[] parts = splitCode(code);
        String coinId = coinGeckoId(parts[0]);
        if (coinId == null)
            return CompletableFuture.failedFuture(
                    new InterfaceSkipException("CoinGecko 不支持代币: " + parts[0]));
        String vsCurrency = parts[1].toLowerCase();
        String url = "https://api.coingecko.com/api/v3/simple/price"
                + "?ids=" + coinId
                + "&vs_currencies=" + URLEncoder.encode(vsCurrency, StandardCharsets.UTF_8)
                + "&include_24hr_change=true"
                + "&x_cg_demo_api_key=" + entry.apiKey();
        return sendGet(url).thenApply(resp -> {
            if (resp.statusCode() == 429)
                throw new RateLimitException("CoinGecko 429: " + code);
            if (resp.statusCode() != 200)
                throw new InterfaceSkipException("CoinGecko HTTP " + resp.statusCode() + ": " + code);
            String body = resp.body();
            // 响应格式: {"bitcoin":{"usd":67432,"usd_24h_change":2.5}}
            double price = extractDouble(body, vsCurrency);
            if (price == 0) throw new InterfaceSkipException("CoinGecko 价格为 0: " + code);
            double changePct = extractDouble(body, vsCurrency + "_24h_change");
            double changeAmount = price / (1 + changePct / 100.0) * (changePct / 100.0);
            String name = parts[0] + "/" + parts[1];
            return new StockInfo(code.toUpperCase(), name, price, changeAmount, changePct);
        });
    }

    /**
     * BASE 符号 → CoinGecko coin id 映射（常用币种）。
     * 返回 null 表示不在列表中，会跳过 CoinGecko。
     */
    private static String coinGeckoId(String symbol) {
        return switch (symbol.toUpperCase()) {
            case "BTC"   -> "bitcoin";
            case "ETH"   -> "ethereum";
            case "BNB"   -> "binancecoin";
            case "SOL"   -> "solana";
            case "XRP"   -> "ripple";
            case "ADA"   -> "cardano";
            case "DOGE"  -> "dogecoin";
            case "DOT"   -> "polkadot";
            case "AVAX"  -> "avalanche-2";
            case "MATIC" -> "matic-network";
            case "LTC"   -> "litecoin";
            case "LINK"  -> "chainlink";
            case "UNI"   -> "uniswap";
            case "ATOM"  -> "cosmos";
            case "XLM"   -> "stellar";
            case "TRX"   -> "tron";
            case "TON"   -> "the-open-network";
            case "SUI"   -> "sui";
            case "PEPE"  -> "pepe";
            case "WIF"   -> "dogwifhat";
            default      -> null;
        };
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private CompletableFuture<HttpResponse<String>> sendGet(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
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

    private double extractDouble(String json, String field) {
        String key = "\"" + field + "\":";
        int s = json.indexOf(key);
        if (s < 0) return 0.0;
        s += key.length();
        // 跳过可能的引号（部分 API 把数字包在字符串里）
        if (s < json.length() && json.charAt(s) == '"') s++;
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e))
                || json.charAt(e) == '-' || json.charAt(e) == '.')) e++;
        try { return Double.parseDouble(json.substring(s, e)); } catch (Exception ex) { return 0.0; }
    }

    /**
     * 提取 Kraken 风格的数组首元素：`"c":["67432.0","0.01"]` → 67432.0
     */
    private double extractKrakenArrayFirst(String json, String field) {
        String key = "\"" + field + "\":[\"";
        int s = json.indexOf(key);
        if (s < 0) return 0.0;
        s += key.length();
        int e = json.indexOf('"', s);
        if (e < 0) return 0.0;
        try { return Double.parseDouble(json.substring(s, e)); } catch (Exception ex) { return 0.0; }
    }

    private static Throwable unwrap(Throwable ex) {
        return (ex.getCause() != null) ? ex.getCause() : ex;
    }

    @Override
    public CompletableFuture<List<KLinePoint>> fetchKLine(String code, int days) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("加密货币暂不支持 K 线: " + code));
    }
}
