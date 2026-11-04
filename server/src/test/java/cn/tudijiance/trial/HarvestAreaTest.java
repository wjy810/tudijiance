package cn.tudijiance.trial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class HarvestAreaTest {

    @Test
    void computesFromRecordBookParameters() {
        // 记载本：收获面积 = 收获长 ×［(小区行数 − 1) × 行距 + 畦埂宽］
        Map<String, String> info = Map.of("harvestLength", "10", "rowsPerPlot", "6", "rowSpacing", "25", "ridgeWidth", "50");
        assertThat(HarvestArea.of(info)).isEqualByComparingTo("17.50");
        // 畦埂宽可以是 0
        assertThat(HarvestArea.of(Map.of("harvestLength", "10", "rowsPerPlot", "6", "rowSpacing", "20", "ridgeWidth", "0")))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void manualAreaWinsAndBadInputGivesNull() {
        assertThat(HarvestArea.of(Map.of("harvestArea", "13.34", "harvestLength", "10"))).isEqualByComparingTo("13.34");
        assertThat(HarvestArea.of(Map.of("harvestLength", "10", "rowsPerPlot", "6"))).isNull();
        assertThat(HarvestArea.of(Map.of("harvestLength", "abc", "rowsPerPlot", "6", "rowSpacing", "20", "ridgeWidth", "0")))
                .isNull();
        assertThat(HarvestArea.of(Map.of("harvestLength", "10", "rowsPerPlot", "1", "rowSpacing", "20", "ridgeWidth", "0")))
                .isNull();
        assertThat(HarvestArea.of(Map.of())).isNull();
    }

    @Test
    void fallsBackToPlotLength() {
        assertThat(HarvestArea.of(Map.of("plotLength", "6.65", "rowsPerPlot", "8", "rowSpacing", "20", "ridgeWidth", "40")))
                .isEqualByComparingTo("11.97");
    }
}
