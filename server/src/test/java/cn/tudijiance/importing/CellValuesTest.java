package cn.tudijiance.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import cn.tudijiance.trait.TraitOption;

class CellValuesTest {

    @Test
    void readsDatesInCommonChineseFormats() {
        LocalDate d = LocalDate.of(2025, 10, 12);
        assertThat(CellValues.date("2025-10-12", 2026)).isEqualTo(d);
        assertThat(CellValues.date("2025/10/12", 2026)).isEqualTo(d);
        assertThat(CellValues.date("2025年10月12日", 2026)).isEqualTo(d);
        assertThat(CellValues.date(new BigDecimal("20251012"), 2026)).isEqualTo(d);
        // 只有月日：8 月以后算播种那一年
        assertThat(CellValues.date("10月12日", 2026)).isEqualTo(d);
        assertThat(CellValues.date(new BigDecimal("10.12"), 2026)).isEqualTo(d);
        assertThat(CellValues.date("6-2", 2026)).isEqualTo(LocalDate.of(2026, 6, 2));
        // 没设日期格式的 Excel 日期序号
        assertThat(CellValues.date(new BigDecimal("45942"), 2026)).isEqualTo(d);
        assertThat(CellValues.date("明天", 2026)).isNull();
        assertThat(CellValues.date("2025-13-40", 2026)).isNull();
    }

    @Test
    void readsNumbersWithUnits() {
        assertThat(CellValues.number("78.5cm")).isEqualByComparingTo("78.5");
        assertThat(CellValues.number(" 12 % ")).isEqualByComparingTo("12");
        assertThat(CellValues.number("1,234.5")).isEqualByComparingTo("1234.5");
        assertThat(CellValues.number("abc")).isNull();
        assertThat(CellValues.number("1-2")).isNull();
    }

    @Test
    void readsReplicatesAndCodes() {
        assertThat(CellValues.replicate("II")).isEqualTo(2);
        assertThat(CellValues.replicate("Ⅲ")).isEqualTo(3);
        assertThat(CellValues.replicate("重复1")).isEqualTo(1);
        assertThat(CellValues.replicate(new BigDecimal("2"))).isEqualTo(2);
        assertThat(CellValues.replicate("1.5")).isNull();
        assertThat(CellValues.code(new BigDecimal("101.0"))).isEqualTo("101");
    }

    @Test
    void matchesOptionsByCodeLabelOrUniquePart() {
        List<TraitOption> grades = List.of(option("1", "1 级 免疫"), option("2", "2 级 高抗"), option("3", "3 级 中抗"));
        assertThat(CellValues.option("2级", grades).getValueCode()).isEqualTo("2");
        assertThat(CellValues.option(new BigDecimal("3"), grades).getValueCode()).isEqualTo("3");
        assertThat(CellValues.option("中抗", grades).getValueCode()).isEqualTo("3");
        assertThat(CellValues.option("级", grades)).isNull();
        assertThat(CellValues.option("9级", grades)).isNull();
    }

    private static TraitOption option(String code, String label) {
        TraitOption o = new TraitOption();
        o.setValueCode(code);
        o.setLabel(label);
        return o;
    }
}
