package cn.tudijiance.trial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 记载本"产量表"下面的计产面积：
 * 收获面积 = 收获长 ×［(小区行数 − 1) × 行距 + 畦埂宽］，长度用米，行距和畦埂宽用厘米。
 * 直接填了收获面积时用填的；收获长没填时用小区长度。
 */
public final class HarvestArea {

    public static final String MANUAL = "harvestArea";
    public static final String LENGTH = "harvestLength";
    /** 收获长没填时用试验设计里的小区长度 */
    public static final String PLOT_LENGTH = "plotLength";
    public static final String ROWS = "rowsPerPlot";
    public static final String ROW_SPACING = "rowSpacing";
    public static final String RIDGE = "ridgeWidth";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX = new BigDecimal("999999");

    private HarvestArea() {
    }

    /** 算不出来（缺参数、不是正数）时返回 null */
    public static BigDecimal of(Map<String, String> info) {
        BigDecimal manual = positive(info.get(MANUAL));
        if (manual != null) {
            return cap(manual);
        }
        BigDecimal length = positive(info.get(LENGTH));
        if (length == null) {
            length = positive(info.get(PLOT_LENGTH));
        }
        BigDecimal rows = positive(info.get(ROWS));
        BigDecimal spacing = positive(info.get(ROW_SPACING));
        BigDecimal ridge = nonNegative(info.get(RIDGE));
        if (length == null || rows == null || spacing == null || ridge == null) {
            return null;
        }
        BigDecimal widthCm = rows.subtract(BigDecimal.ONE).multiply(spacing).add(ridge);
        if (widthCm.signum() <= 0) {
            return null;
        }
        return cap(length.multiply(widthCm).divide(HUNDRED, 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal cap(BigDecimal v) {
        return v.compareTo(MAX) > 0 ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(String s) {
        BigDecimal v = number(s);
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static BigDecimal nonNegative(String s) {
        BigDecimal v = number(s);
        return v == null || v.signum() < 0 ? null : v;
    }

    private static BigDecimal number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
