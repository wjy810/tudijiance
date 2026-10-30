package cn.tudijiance.ingest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceMapper;
import cn.tudijiance.device.SensorMetric;
import cn.tudijiance.device.SensorMetricMapper;
import cn.tudijiance.device.SensorReading;
import cn.tudijiance.device.SensorReadingMapper;

/**
 * 传感器数据入库的唯一入口。MQTT、HTTP 和模拟器三种来源都调用这里，
 * 所以换硬件、换接入方式时，校验、去重、入库和设备状态的逻辑都不用改。
 */
@Service
public class SensorIngestService {

    private static final Logger log = LoggerFactory.getLogger(SensorIngestService.class);
    /** 设备时钟可能有偏差，允许比服务器时间快 10 分钟以内 */
    private static final long MAX_FUTURE_MINUTES = 10;

    private final SensorReadingMapper readingMapper;
    private final SensorMetricMapper metricMapper;
    private final DeviceMapper deviceMapper;
    private final Clock clock;

    public SensorIngestService(SensorReadingMapper readingMapper, SensorMetricMapper metricMapper,
                               DeviceMapper deviceMapper, Clock clock) {
        this.readingMapper = readingMapper;
        this.metricMapper = metricMapper;
        this.deviceMapper = deviceMapper;
        this.clock = clock;
    }

    public IngestResult ingest(Device device, List<ReadingRecord> records, String source) {
        Map<String, SensorMetric> metrics = metricMapper.selectList(null).stream()
                .collect(Collectors.toMap(SensorMetric::getCode, Function.identity()));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int accepted = 0;
        int duplicates = 0;
        List<String> rejected = new ArrayList<>();

        for (ReadingRecord record : records) {
            LocalDateTime measuredAt = record.measuredAt() == null
                    ? now : record.measuredAt().truncatedTo(ChronoUnit.SECONDS);
            if (measuredAt.isAfter(now.plusMinutes(MAX_FUTURE_MINUTES))) {
                rejected.add(measuredAt + " 晚于服务器时间，请检查设备时钟");
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : record.values().entrySet()) {
                SensorMetric metric = metrics.get(e.getKey());
                if (metric == null) {
                    rejected.add(e.getKey() + "：未知的测量项");
                    continue;
                }
                if (!Boolean.TRUE.equals(metric.getEnabled())) {
                    rejected.add(e.getKey() + "：测量项未启用");
                    continue;
                }
                SensorReading reading = new SensorReading();
                reading.setDeviceId(device.getId());
                reading.setMetric(metric.getCode());
                reading.setMetricValue(e.getValue());
                reading.setMeasuredAt(measuredAt);
                reading.setReceivedAt(now);
                reading.setSource(source);
                reading.setOutOfRange(outOfRange(metric, e.getValue()));
                try {
                    readingMapper.insert(reading);
                    accepted++;
                } catch (DuplicateKeyException dup) {
                    duplicates++;
                }
            }
        }

        if (accepted > 0 || duplicates > 0) {
            deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                    .set(Device::getStatus, Device.STATUS_ONLINE)
                    .set(Device::getLastSeenAt, now)
                    .eq(Device::getId, device.getId()));
        }
        if (!rejected.isEmpty()) {
            log.warn("设备 {} 的部分数据被拒绝：{}", device.getCode(), rejected);
        }
        return new IngestResult(accepted, duplicates, rejected);
    }

    private static boolean outOfRange(SensorMetric metric, BigDecimal value) {
        return (metric.getMinValid() != null && value.compareTo(metric.getMinValid()) < 0)
                || (metric.getMaxValid() != null && value.compareTo(metric.getMaxValid()) > 0);
    }

    /** accepted 新入库条数；duplicates 重复（已存在）条数；rejected 被拒绝的原因。 */
    public record IngestResult(int accepted, int duplicates, List<String> rejected) {
    }
}
