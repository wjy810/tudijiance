package cn.tudijiance.ingest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一次测量：某个时刻的一组读数，例如 {soil_temp: 12.3, soil_moisture: 23.5, soil_ph: 7.12}。
 * measuredAt 为 null 时用服务器收到的时间。
 */
public record ReadingRecord(LocalDateTime measuredAt, Map<String, BigDecimal> values) {
}
