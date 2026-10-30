package cn.tudijiance.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SoilSimulationModelTest {

    @Test
    void sameDeviceAndTimeGiveSameValues() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 30, 14, 30);
        assertThat(SoilSimulationModel.values(7, t)).isEqualTo(SoilSimulationModel.values(7, t));
    }

    @Test
    void valuesStayInPlausibleRangesForAYear() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        boolean sawWetting = false;
        for (int i = 0; i < 365 * 4; i++, t = t.plusHours(6)) {
            Map<String, BigDecimal> v = SoilSimulationModel.values(1, t);
            assertThat(v.get("soil_temp").doubleValue()).isBetween(-5.0, 30.0);
            assertThat(v.get("soil_moisture").doubleValue()).isBetween(19.0, 36.0);
            assertThat(v.get("soil_ph").doubleValue()).isBetween(7.1, 7.5);
            sawWetting |= v.get("soil_moisture").doubleValue() > 28;
        }
        assertThat(sawWetting).as("一年里应该出现过降雨或灌溉").isTrue();
    }

    @Test
    void afternoonIsWarmerThanEarlyMorning() {
        LocalDateTime day = LocalDateTime.of(2026, 5, 10, 0, 0);
        double morning = SoilSimulationModel.temperature(3, day.withHour(4));
        double afternoon = SoilSimulationModel.temperature(3, day.withHour(16));
        assertThat(afternoon).isGreaterThan(morning + 3);
    }
}
