package cn.tudijiance.device;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record DeviceRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "只能包含字母、数字、下划线和短横线")
            String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank String deviceType,
            Long fieldId,
            BigDecimal posRow,
            BigDecimal posCol,
            @Min(0) @Max(200) Integer depthCm,
            Boolean simulated,
            @Size(max = 500) String remark) {
    }

    public record LatestValue(BigDecimal value, LocalDateTime measuredAt, boolean outOfRange) {
    }

    public record DeviceView(
            Long id, String code, String name, String deviceType, Long fieldId,
            BigDecimal posRow, BigDecimal posCol, Integer depthCm, boolean simulated,
            String status, LocalDateTime lastSeenAt, String remark,
            String mqttTopic, Map<String, LatestValue> latest) {
    }

    /** 新建设备或重新生成令牌时返回。token 只在这里显示一次，服务器只保存哈希。 */
    public record DeviceWithToken(DeviceView device, String token) {
    }

    public enum Bucket { RAW, HOUR, DAY }

    public record SeriesPoint(LocalDateTime t, BigDecimal value, BigDecimal min, BigDecimal max) {
    }

    public record Series(String metric, String unit, Bucket bucket, List<SeriesPoint> points) {
    }
}
