package cn.tudijiance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.common.AppProperties;
import tools.jackson.databind.json.JsonMapper;

class JsonPayloadDecoderTest {

    private final JsonPayloadDecoder decoder = new JsonPayloadDecoder(JsonMapper.builder().build(),
            new AppProperties("Asia/Shanghai", null, null, new AppProperties.Device(Duration.ofHours(2)), null, null));

    @Test
    void parsesEpochSecondsAndMillis() {
        // 1785369600 = 2026-07-30T00:00:00Z = 北京时间 08:00
        assertThat(decode("{\"ts\":1785369600,\"values\":{\"soil_temp\":20}}").get(0).measuredAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 8, 0));
        assertThat(decode("{\"ts\":1785369600000,\"values\":{\"soil_temp\":20}}").get(0).measuredAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 8, 0));
    }

    @Test
    void convertsIsoTimeWithOffsetToServerZone() {
        ReadingRecord r = decode("{\"ts\":\"2026-07-30T00:00:00Z\",\"values\":{\"soil_ph\":\"7.1\"}}").get(0);
        assertThat(r.measuredAt()).isEqualTo(LocalDateTime.of(2026, 7, 30, 8, 0));
        assertThat(r.values().get("soil_ph")).isEqualByComparingTo("7.1");
    }

    @Test
    void missingTimestampMeansReceiveTime() {
        assertThat(decode("{\"values\":{\"soil_temp\":20}}").get(0).measuredAt()).isNull();
    }

    @Test
    void parsesBatch() {
        List<ReadingRecord> records = decode("""
                {"records":[{"ts":"2026-07-30T08:00:00","values":{"soil_temp":20}},
                            {"ts":"2026-07-30T08:30:00","values":{"soil_temp":21}}]}
                """);
        assertThat(records).hasSize(2);
        assertThat(records.get(1).measuredAt()).isEqualTo(LocalDateTime.of(2026, 7, 30, 8, 30));
    }

    @Test
    void rejectsBadPayloads() {
        assertThatThrownBy(() -> decode("hello")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> decode("{\"values\":{}}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> decode("{\"values\":{\"soil_temp\":\"abc\"}}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> decode("{\"ts\":\"yesterday\",\"values\":{\"soil_temp\":1}}"))
                .isInstanceOf(ApiException.class);
    }

    private List<ReadingRecord> decode(String s) {
        return decoder.decode(s.getBytes(StandardCharsets.UTF_8));
    }
}
