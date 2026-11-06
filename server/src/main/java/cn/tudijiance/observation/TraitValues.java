package cn.tudijiance.observation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;

/**
 * 按项目的数据类型检查录入的值，并生成显示用的文字。
 *
 * <pre>
 * DATE       日期：value_date，例如出苗期
 * NUMBER     数值：value_num，按项目的小数位数四舍五入
 * GRADE      分级：value_num，必须是分级选项之一
 * OPTION     选项：value_text，必须是选项之一
 * TEXT       文字：value_text
 * OPERATION  农事：value_text 是类型（从选项里选），value_num 是用量（可不填）
 * </pre>
 */
public final class TraitValues {

    private TraitValues() {
    }

    public record Value(BigDecimal num, String text, LocalDate date) {
    }

    public static Value validate(TraitDef trait, List<TraitOption> options, BigDecimal num, String text, LocalDate date) {
        String t = text == null || text.isBlank() ? null : text.trim();
        String name = trait.getName();
        return switch (trait.getDataType()) {
            case "DATE" -> {
                if (date == null) {
                    throw ApiException.badRequest(name + "：请选择日期");
                }
                yield new Value(null, null, date);
            }
            case "NUMBER" -> {
                if (num == null) {
                    throw ApiException.badRequest(name + "：请填写数值");
                }
                if (!options.isEmpty() && options.stream().noneMatch(o -> new BigDecimal(o.getValueCode()).compareTo(num) == 0)) {
                    throw ApiException.badRequest(name + "：只能选择规定的档位");
                }
                yield new Value(round(trait, num), null, null);
            }
            case "GRADE" -> {
                if (num == null || options.stream().noneMatch(o -> o.getValueCode().equals(num.stripTrailingZeros().toPlainString()))) {
                    throw ApiException.badRequest(name + "：请选择级别");
                }
                yield new Value(num.stripTrailingZeros(), null, null);
            }
            case "OPTION" -> {
                if (t == null || options.stream().noneMatch(o -> o.getValueCode().equals(t))) {
                    throw ApiException.badRequest(name + "：请从选项里选择");
                }
                yield new Value(null, t, null);
            }
            case "OPERATION" -> {
                if (t == null || (!options.isEmpty() && options.stream().noneMatch(o -> o.getValueCode().equals(t)))) {
                    throw ApiException.badRequest(name + "：请选择类型");
                }
                if (num != null && num.signum() < 0) {
                    throw ApiException.badRequest(name + "：用量不能是负数");
                }
                yield new Value(num == null ? null : round(trait, num), t, null);
            }
            case "TEXT" -> {
                if (t == null) {
                    throw ApiException.badRequest(name + "：请填写内容");
                }
                yield new Value(null, t, null);
            }
            default -> throw ApiException.badRequest(name + "：这个项目不能手动录入");
        };
    }

    public static String display(TraitDef trait, List<TraitOption> options, BigDecimal num, String text, LocalDate date) {
        String unit = trait.getUnit() == null || "级".equals(trait.getUnit()) ? "" : " " + trait.getUnit();
        return switch (trait.getDataType()) {
            case "DATE" -> date == null ? "" : date.toString();
            case "GRADE" -> {
                String code = num == null ? "" : num.stripTrailingZeros().toPlainString();
                yield options.stream().filter(o -> o.getValueCode().equals(code)).findFirst()
                        .map(TraitOption::getLabel).orElse(code + " 级");
            }
            case "NUMBER" -> num == null ? "" : num.stripTrailingZeros().toPlainString() + unit;
            case "OPERATION" -> (text == null ? "" : text)
                    + (num == null ? "" : " " + num.stripTrailingZeros().toPlainString() + unit);
            default -> text == null ? "" : text;
        };
    }

    private static BigDecimal round(TraitDef trait, BigDecimal num) {
        return trait.getPrecisionDigits() == null ? num : num.setScale(trait.getPrecisionDigits(), RoundingMode.HALF_UP);
    }
}
