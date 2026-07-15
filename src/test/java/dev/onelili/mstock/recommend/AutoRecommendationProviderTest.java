package dev.onelili.mstock.recommend;

import dev.onelili.mstock.model.StockInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoRecommendationProviderTest {

    @Test
    void parsesEastmoneyCandidateFields() {
        String json = """
                {"rc":0,"data":{"diff":[
                  {"f2":12.50,"f3":2.40,"f5":123,"f6":99887766.0,"f8":4.5,"f10":1.6,"f12":"000001","f14":"平安银行"},
                  {"f2":"-","f3":"-","f6":0,"f8":"-","f10":"-","f12":"000002","f14":"停牌股"}
                ]}}
                """;

        List<AutoRecommendationProvider.MarketCandidate> candidates =
                AutoRecommendationProvider.parseCandidates(json);

        assertEquals(1, candidates.size());
        StockInfo info = candidates.getFirst().info();
        assertEquals("000001", info.getCode());
        assertEquals("平安银行", info.getName());
        assertEquals(12.50, info.getPrice(), 0.0001);
        assertEquals(2.40, info.getChangePercent(), 0.0001);
        assertEquals(4.5, candidates.getFirst().turnoverRate(), 0.0001);
        assertEquals(1.6, candidates.getFirst().volumeRatio(), 0.0001);
    }

    @Test
    void filtersRiskNamesAndExtremeMoves() {
        AutoRecommendationProvider.MarketCandidate normal = candidate("普通股份", 3.0);
        AutoRecommendationProvider.MarketCandidate st = candidate("*ST测试", 3.0);
        AutoRecommendationProvider.MarketCandidate limitUp = candidate("测试涨停", 10.0);

        assertTrue(AutoRecommendationProvider.isEligible(normal));
        assertFalse(AutoRecommendationProvider.isEligible(st));
        assertFalse(AutoRecommendationProvider.isEligible(limitUp));
    }

    @Test
    void scoreRewardsHealthyMomentumAndActivity() {
        AutoRecommendationProvider.MarketCandidate active = new AutoRecommendationProvider.MarketCandidate(
                new StockInfo("000001", "活跃股", 10, 0.3, 3.0),
                1_000_000_000, 8.0, 2.0);
        AutoRecommendationProvider.MarketCandidate weak = new AutoRecommendationProvider.MarketCandidate(
                new StockInfo("000002", "弱势股", 10, -0.3, -3.0),
                100_000_000, 2.0, 0.8);

        assertTrue(AutoRecommendationProvider.score(active)
                > AutoRecommendationProvider.score(weak));
    }

    private static AutoRecommendationProvider.MarketCandidate candidate(String name, double change) {
        return new AutoRecommendationProvider.MarketCandidate(
                new StockInfo("000001", name, 10, change / 10, change),
                100_000_000, 3.0, 1.2);
    }
}
