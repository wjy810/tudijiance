package cn.tudijiance.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 土壤三合一传感器的模拟数据（埋深约 10 cm）。同一台设备、同一时刻算出的值总是相同，便于测试和去重。
 *
 * - 温度：季节变化（1 月最低、7 月最高）+ 日变化（午后最高），幅度比气温小。
 * - 湿度：平时缓慢变干；随机的降雨或灌溉日会突然变湿，然后在几天内回落。
 * - pH：基本稳定在 7.3 左右，有很小的波动。
 *
 * 只用于硬件到货前的开发和演示，不代表真实田间情况。
 */
public final class SoilSimulationModel {

    /** 每天有 18% 的概率发生一次降雨或灌溉 */
    private static final int WET_DAY_PERCENT = 18;
    private static final int LOOKBACK_DAYS = 12;

    private SoilSimulationModel() {
    }

    public static Map<String, BigDecimal> values(long seed, LocalDateTime t) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("soil_temp", round(temperature(seed, t), 1));
        values.put("soil_moisture", round(moisture(seed, t), 1));
        values.put("soil_ph", round(ph(seed, t), 2));
        return values;
    }

    static double temperature(long seed, LocalDateTime t) {
        double dayOfYear = t.getDayOfYear();
        double hour = t.getHour() + t.getMinute() / 60.0;
        double seasonal = 13 + 11 * Math.sin(2 * Math.PI * (dayOfYear - 110) / 365.0);
        double daily = 3 * Math.sin(2 * Math.PI * (hour - 10) / 24.0);
        return seasonal + daily + noise(seed, t, 1) * 0.3;
    }

    static double moisture(long seed, LocalDateTime t) {
        double base = 21;
        for (int d = 0; d <= LOOKBACK_DAYS; d++) {
            LocalDate day = t.toLocalDate().minusDays(d);
            LocalDateTime wettedAt = day.atTime(12, 0);
            if (isWetDay(seed, day) && !wettedAt.isAfter(t)) {
                double hoursSince = Duration.between(wettedAt, t).toMinutes() / 60.0;
                return base + 13 * Math.exp(-hoursSince / 96.0) + noise(seed, t, 2) * 0.3;
            }
        }
        return base + noise(seed, t, 2) * 0.3;
    }

    static double ph(long seed, LocalDateTime t) {
        return 7.3 + 0.08 * Math.sin(2 * Math.PI * t.getDayOfYear() / 365.0) + noise(seed, t, 3) * 0.03;
    }

    private static boolean isWetDay(long seed, LocalDate day) {
        return new SplittableRandom(seed * 1_000_003L + day.toEpochDay()).nextInt(100) < WET_DAY_PERCENT;
    }

    /** -1 到 1 之间的确定性随机数 */
    private static double noise(long seed, LocalDateTime t, int salt) {
        long minute = t.toEpochSecond(ZoneOffset.UTC) / 60;
        return new SplittableRandom(seed * 7_919L + minute * 31L + salt).nextDouble() * 2 - 1;
    }

    private static BigDecimal round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
