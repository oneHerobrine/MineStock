package dev.onelili.mstock.stockio;

import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HkStockApiTest {

    private final HkStockApi api = new HkStockApi(Logger.getAnonymousLogger());

    @Test
    void acceptsOneToFiveDigitCodes() {
        assertTrue(api.supports("5"));
        assertTrue(api.supports("700"));
        assertTrue(api.supports("09988"));
        assertFalse(api.supports("123456"));
        assertFalse(api.supports("AAPL"));
    }

    @Test
    void parsesSinaHkQuoteFields() {
        String body = "var hq_str_hk00700=\"TENCENT,腾讯控股,463.200,460.200,"
                + "473.800,456.200,457.600,-2.600,-0.565,457.60001\";";

        StockInfo info = HkStockApi.parseSina("700", body);

        assertEquals("700", info.getCode());
        assertEquals("腾讯控股", info.getName());
        assertEquals(457.6, info.getPrice(), 0.0001);
        assertEquals(-2.6, info.getChangeAmount(), 0.0001);
        assertEquals(-0.56497, info.getChangePercent(), 0.0001);
    }

    @Test
    void parsesTencentHkQuoteFields() {
        String[] fields = new String[47];
        java.util.Arrays.fill(fields, "0");
        fields[1] = "腾讯控股";
        fields[3] = "457.600";
        fields[4] = "460.200";
        fields[31] = "-2.600";
        fields[32] = "-0.56";

        StockInfo info = HkStockApi.parseTencent(
                "700", "v_hk00700=\"" + String.join("~", fields) + "\";");

        assertEquals("腾讯控股", info.getName());
        assertEquals(457.6, info.getPrice(), 0.0001);
        assertEquals(-2.6, info.getChangeAmount(), 0.0001);
        assertEquals(-0.56, info.getChangePercent(), 0.0001);
    }

    @Test
    void parsesTencentDailyKLine() {
        String body = """
                {
                  "code": 0,
                  "msg": "",
                  "data": {
                    "hk00700": {
                      "day": [
                        ["2026-07-10", "472.800", "460.200", "473.600", "458.800", "40440298.000"],
                        ["2026-07-13", "463.200", "457.600", "473.800", "456.200", "24291842.000"]
                      ]
                    }
                  }
                }
                """;

        List<KLinePoint> points = HkStockApi.parseTencentKLine(body, "hk00700");

        assertEquals(2, points.size());
        KLinePoint latest = points.get(1);
        assertEquals("2026-07-13", latest.getDate());
        assertEquals(463.2, latest.getOpen(), 0.0001);
        assertEquals(473.8, latest.getHigh(), 0.0001);
        assertEquals(456.2, latest.getLow(), 0.0001);
        assertEquals(457.6, latest.getClose(), 0.0001);
    }
}
