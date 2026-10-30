package cn.tudijiance.ingest;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.AppProperties;
import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceMapper;

/** 每分钟检查一次：超过 app.device.offline-after（默认 2 小时）没有上报的设备标记为离线。 */
@Component
public class DeviceStatusJob {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusJob.class);

    private final DeviceMapper deviceMapper;
    private final AppProperties properties;
    private final Clock clock;

    public DeviceStatusJob(DeviceMapper deviceMapper, AppProperties properties, Clock clock) {
        this.deviceMapper = deviceMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void markOffline() {
        LocalDateTime threshold = LocalDateTime.now(clock).minus(properties.device().offlineAfter());
        int changed = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .set(Device::getStatus, Device.STATUS_OFFLINE)
                .eq(Device::getStatus, Device.STATUS_ONLINE)
                .lt(Device::getLastSeenAt, threshold));
        if (changed > 0) {
            log.warn("{} 台设备超过 {} 没有上报数据，已标记为离线", changed, properties.device().offlineAfter());
        }
    }
}
