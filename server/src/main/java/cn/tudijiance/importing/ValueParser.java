package cn.tudijiance.importing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Plot;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;

/** 按录入项目的类型解析表格里的一个格子。看不懂时抛出 {@link ApiException}，消息直接显示给老师。 */
final class ValueParser {

    private static final BigDecimal MU = new BigDecimal("666.7");

    private ValueParser() {
    }

    /**
     * 一个字段要导入成哪个项目。
     *
     * @param mapped         老师选的项目（记表头别名时用它）
     * @param stored         实际保存的项目：选的是"折合亩产"时存成"小区产量"，其它情况和 mapped 相同
     * @param fromYieldPerMu 这个字段是折合亩产，要按小区面积换算成小区产量
     */
    record Target(int field, TraitDef mapped, TraitDef stored, List<TraitOption> options, boolean fromYieldPerMu) {
    }

    record Parsed(BigDecimal num, String text, LocalDate date) {
    }

    /** 格子里写的是"没有数据"：空、"-"、"—"、"/" */
    static boolean isEmpty(Object raw) {
        String s = CellValues.text(raw);
        return s == null || "-".equals(s) || "—".equals(s) || "/".equals(s);
    }

    static Parsed parse(Target t, Object raw, Plot plot, int seasonYear) {
        TraitDef trait = t.stored();
        String shown = CellValues.text(raw);
        return switch (trait.getDataType()) {
            case "DATE" -> {
                LocalDate d = CellValues.date(raw, seasonYear);
                if (d == null) {
                    throw ApiException.badRequest("看不懂的日期：" + shown);
                }
                yield new Parsed(null, null, d);
            }
            case "GRADE" -> {
                TraitOption o = CellValues.option(raw, t.options());
                if (o == null) {
                    throw ApiException.badRequest("\"" + shown + "\"不是" + trait.getName() + "的级别");
                }
                yield new Parsed(new BigDecimal(o.getValueCode()), null, null);
            }
            case "NUMBER" -> new Parsed(number(t, raw, shown, plot), null, null);
            case "OPTION", "OPERATION" -> {
                if (t.options().isEmpty()) {
                    yield new Parsed(null, shown, null);
                }
                TraitOption o = CellValues.option(raw, t.options());
                if (o == null) {
                    throw ApiException.badRequest("\"" + shown + "\"不在" + trait.getName() + "的选项里");
                }
                yield new Parsed(null, o.getValueCode(), null);
            }
            default -> new Parsed(null, shown, null);
        };
    }

    private static BigDecimal number(Target t, Object raw, String shown, Plot plot) {
        BigDecimal n = CellValues.number(raw);
        if (n == null && !t.options().isEmpty()) {
            TraitOption o = CellValues.option(raw, t.options());
            n = o == null ? null : new BigDecimal(o.getValueCode());
        }
        if (n == null) {
            throw ApiException.badRequest("\"" + shown + "\"不是数字");
        }
        if (!t.fromYieldPerMu()) {
            return n;
        }
        if (plot.getLengthM() == null || plot.getWidthM() == null) {
            throw ApiException.badRequest("小区 " + plot.getCode() + " 没有填长和宽，不能由亩产换算小区产量");
        }
        return n.multiply(plot.getLengthM().multiply(plot.getWidthM())).divide(MU, 2, RoundingMode.HALF_UP);
    }
}
