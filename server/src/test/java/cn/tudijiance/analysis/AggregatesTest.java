package cn.tudijiance.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import cn.tudijiance.analysis.AnalysisDtos.Agg;
import cn.tudijiance.analysis.AnalysisService.Aggregates;

class AggregatesTest {

    private static final List<BigDecimal> VALUES = List.of(new BigDecimal("600"), new BigDecimal("620"),
            new BigDecimal("580"), new BigDecimal("610"));

    @Test
    void basicAggregations() {
        assertThat(Aggregates.of(Agg.AVG, VALUES, 1)).isEqualByComparingTo("602.5");
        assertThat(Aggregates.of(Agg.SUM, VALUES, 0)).isEqualByComparingTo("2410");
        assertThat(Aggregates.of(Agg.MAX, VALUES, 0)).isEqualByComparingTo("620");
        assertThat(Aggregates.of(Agg.MIN, VALUES, 0)).isEqualByComparingTo("580");
        assertThat(Aggregates.of(Agg.MEDIAN, VALUES, 1)).isEqualByComparingTo("605.0");
        assertThat(Aggregates.of(Agg.COUNT, VALUES, 0)).isEqualByComparingTo("4");
    }

    @Test
    void coefficientOfVariationUsesSampleStandardDeviation() {
        // 样本标准差 17.08，平均 602.5，变异系数 2.8%
        assertThat(Aggregates.of(Agg.CV, VALUES, 1)).isEqualByComparingTo("2.8");
        assertThat(Aggregates.of(Agg.CV, List.of(new BigDecimal("5")), 1)).isNull();
        assertThat(Aggregates.of(Agg.CV, List.of(BigDecimal.ZERO, BigDecimal.ZERO), 1)).isNull();
    }

    @Test
    void emptyGroupsHaveNoValueExceptCount() {
        assertThat(Aggregates.of(Agg.AVG, List.of(), 1)).isNull();
        assertThat(Aggregates.of(Agg.COUNT, List.of(), 0)).isEqualByComparingTo("0");
    }
}
