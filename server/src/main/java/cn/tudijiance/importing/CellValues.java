package cn.tudijiance.importing;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DateUtil;

import cn.tudijiance.trait.TraitOption;

/** 把表格里的格子按项目类型解析成数值、日期或选项。解析不了返回 null，由调用方报错。 */
final class CellValues {

    private static final Pattern NUMBER = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[^\\d.]*$");
    private static final Pattern FULL_DATE = Pattern.compile("^(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?$");
    private static final Pattern COMPACT_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern MONTH_DAY = Pattern.compile("^(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?$");

    private CellValues() {
    }

    static String text(Object v) {
        String s = SheetReader.display(v).strip();
        return s.isEmpty() ? null : s;
    }

    /** 数字；"78.5"、"78.5cm"、"12%" 都可以 */
    static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof String s) {
            Matcher m = NUMBER.matcher(s.replace("，", ".").replace(",", ""));
            return m.matches() ? new BigDecimal(m.group(1)) : null;
        }
        return null;
    }

    /**
     * 日期：Excel 日期格子、2025-10-12、2025/10/12、2025.10.12、2025年10月12日、20251012；
     * 只有月日（10月12日、10-12、10.12）时按试验年份补全：8 月以后的算播种那一年（收获年份减 1）。
     */
    static LocalDate date(Object v, int seasonYear) {
        if (v instanceof LocalDate d) {
            return d;
        }
        try {
            if (v instanceof BigDecimal d) {
                String s = d.toPlainString();
                Matcher compact = COMPACT_DATE.matcher(s);
                if (compact.matches()) {
                    return LocalDate.of(Integer.parseInt(compact.group(1)), Integer.parseInt(compact.group(2)),
                            Integer.parseInt(compact.group(3)));
                }
                // 没设成日期格式的 Excel 日期序号
                double serial = d.doubleValue();
                if (serial > 20000 && serial < 80000) {
                    return DateUtil.getLocalDateTime(serial).toLocalDate();
                }
                // 10.12 这样的月.日
                return monthDay(s, seasonYear);
            }
            if (v instanceof String s) {
                String t = s.strip();
                Matcher full = FULL_DATE.matcher(t);
                if (full.matches()) {
                    return LocalDate.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)),
                            Integer.parseInt(full.group(3)));
                }
                Matcher compact = COMPACT_DATE.matcher(t);
                if (compact.matches()) {
                    return LocalDate.of(Integer.parseInt(compact.group(1)), Integer.parseInt(compact.group(2)),
                            Integer.parseInt(compact.group(3)));
                }
                return monthDay(t, seasonYear);
            }
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static LocalDate monthDay(String s, int seasonYear) {
        Matcher m = MONTH_DAY.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        return LocalDate.of(month >= 8 ? seasonYear - 1 : seasonYear, month, day);
    }

    /** 重复号：1、2、3，I、II、III，重复1、Ⅱ */
    static Integer replicate(Object v) {
        String s = text(v);
        if (s == null) {
            return null;
        }
        s = s.replace("重复", "").replace("区组", "").strip().toUpperCase();
        s = s.replace("Ⅰ", "I").replace("Ⅱ", "II").replace("Ⅲ", "III").replace("Ⅳ", "IV").replace("Ⅴ", "V")
                .replace("Ⅵ", "VI");
        return switch (s) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            default -> {
                BigDecimal n = number(s);
                yield n == null || n.stripTrailingZeros().scale() > 0 ? null : n.intValue();
            }
        };
    }

    /** 小区编号：数字格子 101.0 读成 "101" */
    static String code(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : text(v);
    }

    /**
     * 从选项里找：编码相同、名称相同，或者去掉空格后相同；"3级" 按 3 找；都不行时找名称里唯一包含它的。
     */
    static TraitOption option(Object v, List<TraitOption> options) {
        String s = code(v);
        if (s == null) {
            return null;
        }
        String key = s.replace(" ", "");
        String noGrade = key.endsWith("级") ? key.substring(0, key.length() - 1) : key;
        for (TraitOption o : options) {
            if (o.getValueCode().equals(key) || o.getValueCode().equals(noGrade) || o.getLabel().replace(" ", "").equals(key)) {
                return o;
            }
        }
        List<TraitOption> contains = options.stream().filter(o -> o.getLabel().replace(" ", "").contains(key)).toList();
        return contains.size() == 1 ? contains.getFirst() : null;
    }
}
