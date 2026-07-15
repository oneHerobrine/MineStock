package dev.onelili.mstock.recommend;

import dev.onelili.mstock.model.StockInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches liquid A-share candidates and ranks them without requiring an API key. */
public final class AutoRecommendationProvider implements AutoCloseable {
    private static final String ENDPOINT =
            "https://push2.eastmoney.com/api/qt/clist/get"
            + "?pn=1&pz=%d&po=1&np=1&fltt=2&invt=2&fid=f6"
            + "&fs=m%%3A0%%2Bt%%3A6%%2Cm%%3A0%%2Bt%%3A80%%2Cm%%3A1%%2Bt%%3A2%%2Cm%%3A1%%2Bt%%3A23"
            + "&fields=f2%%2Cf3%%2Cf5%%2Cf6%%2Cf8%%2Cf10%%2Cf12%%2Cf14";
    private static final Pattern OBJECT = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern CODE = Pattern.compile("\\\"f12\\\":\\\"(\\d{6})\\\"");
    private static final Pattern NAME = Pattern.compile("\\\"f14\\\":\\\"([^\\\"]*)\\\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public CompletableFuture<List<StockInfo>> fetchRanked(int candidateCount, int resultCount) {
        int fetchCount = Math.max(resultCount, Math.min(500, candidateCount));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(Locale.ROOT, ENDPOINT, fetchCount)))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "MineStock/1.0")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Eastmoney HTTP " + response.statusCode());
                    }
                    List<MarketCandidate> candidates = parseCandidates(response.body());
                    if (candidates.isEmpty()) {
                        throw new IllegalStateException("Eastmoney returned no usable candidates");
                    }
                    return candidates.stream()
                            .filter(AutoRecommendationProvider::isEligible)
                            .sorted(Comparator.comparingDouble(AutoRecommendationProvider::score).reversed()
                                    .thenComparing(c -> c.info().getCode()))
                            .limit(resultCount)
                            .map(MarketCandidate::info)
                            .toList();
                });
    }

    static List<MarketCandidate> parseCandidates(String json) {
        List<MarketCandidate> result = new ArrayList<>();
        Matcher objects = OBJECT.matcher(json);
        while (objects.find()) {
            String object = objects.group(1);
            Matcher codeMatcher = CODE.matcher(object);
            Matcher nameMatcher = NAME.matcher(object);
            if (!codeMatcher.find() || !nameMatcher.find()) continue;

            String code = codeMatcher.group(1);
            String name = nameMatcher.group(1);
            double price = number(object, "f2");
            double changePercent = number(object, "f3");
            double turnoverAmount = number(object, "f6");
            double turnoverRate = number(object, "f8");
            double volumeRatio = number(object, "f10");
            if (!Double.isFinite(price) || price <= 0) continue;
            double previousClose = changePercent == -100.0
                    ? price
                    : price / (1.0 + changePercent / 100.0);
            double changeAmount = price - previousClose;
            StockInfo info = new StockInfo(code, name, price, changeAmount, changePercent);
            result.add(new MarketCandidate(info, turnoverAmount, turnoverRate, volumeRatio));
        }
        return result;
    }

    static boolean isEligible(MarketCandidate candidate) {
        String name = candidate.info().getName().toUpperCase(Locale.ROOT);
        double change = candidate.info().getChangePercent();
        return !name.contains("ST")
                && !name.contains("退")
                && candidate.turnoverAmount() > 0
                && candidate.turnoverRate() > 0
                && change > -9.5
                && change < 9.5;
    }

    /**
     * Momentum is useful only together with liquidity. The caps prevent one extreme field from
     * dominating the result, while the negative-change penalty avoids recommending falling knives.
     */
    static double score(MarketCandidate candidate) {
        double change = candidate.info().getChangePercent();
        double momentum = change >= 0 ? Math.min(change, 6.0) * 5.0 : change * 7.0;
        double liquidity = Math.log10(Math.max(1.0, candidate.turnoverAmount())) * 3.0;
        double activity = Math.min(candidate.turnoverRate(), 15.0) * 1.5;
        double volume = Math.min(Math.max(candidate.volumeRatio(), 0.0), 3.0) * 8.0;
        return momentum + liquidity + activity + volume;
    }

    private static double number(String object, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\":(?:\\\")?(-?\\d+(?:\\.\\d+)?)(?:\\\")?");
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) return Double.NaN;
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    record MarketCandidate(StockInfo info, double turnoverAmount,
                           double turnoverRate, double volumeRatio) { }
}
