package cn.tudijiance.device;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("sensor_reading")
public class SensorReading {

    public static final String SOURCE_MQTT = "MQTT";
    public static final String SOURCE_HTTP = "HTTP";
    public static final String SOURCE_SIMULATOR = "SIMULATOR";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private String metric;
    private BigDecimal metricValue;
    private LocalDateTime measuredAt;
    private LocalDateTime receivedAt;
    private String source;
    private Boolean outOfRange;
}
