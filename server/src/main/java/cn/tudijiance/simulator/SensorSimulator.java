package cn.tudijiance.simulator;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.AppProperties;
import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceChangedEvent;
import cn.tudijiance.device.DeviceMapper;
import cn.tudijiance.device.DeviceService;
import cn.tudijiance.device.SensorReading;
import cn.tudijiance.device.SensorReadingMapper;
import cn.tudijiance.ingest.ReadingRecord;
import cn.tudijiance.ingest.SensorIngestService;
import jakarta.annotation.PreDestroy;

/**
 * 传感器模拟器：配置 app.simulator.enabled=true 后启用。
 * 给"模拟设备"（simulated = true）定时产生数据，和真实硬件走同一个入库入口 {@link SensorIngestService}。
 * 模拟设备第一次产生数据时，先补出最近 app.simulator.backfill-days 天的历史数据，方便看曲线。
 * 所有模拟工作在同一个后台线程里依次执行，不会互相冲突，也不会拖慢接口请求。
 */
@Component
@ConditionalOnProperty(prefix = "app.simulator", name = "enabled", havingValue = "true")
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);
    private static final int HISTORY_STEP_MINUTES = 30;

    private final DeviceService deviceService;
    private final DeviceMapper deviceMapper;
    private final SensorReadingMapper readingMapper;
    private final SensorIngestService ingestService;
    private final AppProperties properties;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sensor-simulator");
        t.setDaemon(true);
        return t;
    });

    public SensorSimulator(DeviceService deviceService, DeviceMapper deviceMapper, SensorReadingMapper readingMapper,
                           SensorIngestService ingestService, AppProperties properties, Clock clock) {
        this.deviceService = deviceService;
        this.deviceMapper = deviceMapper;
        this.readingMapper = readingMapper;
        this.ingestService = ingestService;
        this.properties = properties;
        this.clock = clock;
        log.warn("传感器模拟器已启用：模拟设备的数据是生成的，不是真实测量值");
    }

    @Scheduled(fixedDelayString = "${app.simulator.interval}", initialDelay = 5_000)
    public void tick() {
        worker.execute(() -> deviceService.simulatedDevices().forEach(this::simulate));
    }

    /** 新建或改成模拟设备后，马上生成数据，不用等下一次定时。 */
    @EventListener
    public void onDeviceChanged(DeviceChangedEvent event) {
        worker.execute(() -> {
            Device device = deviceMapper.selectById(event.deviceId());
            if (device != null && Boolean.TRUE.equals(device.getSimulated())) {
                simulate(device);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    private void simulate(Device device) {
        try {
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
            boolean empty = readingMapper.selectCount(Wrappers.<SensorReading>lambdaQuery()
                    .eq(SensorReading::getDeviceId, device.getId())) == 0;
            List<ReadingRecord> records = empty ? history(device, now) : List.of(record(device, now));
            ingestService.ingest(device, records, SensorReading.SOURCE_SIMULATOR);
            if (empty) {
                log.info("已为模拟设备 {} 生成 {} 条历史测量", device.getCode(), records.size());
            }
        } catch (RuntimeException e) {
            log.warn("模拟设备 {} 生成数据失败：{}", device.getCode(), e.getMessage());
        }
    }

    private List<ReadingRecord> history(Device device, LocalDateTime now) {
        List<ReadingRecord> records = new ArrayList<>();
        LocalDateTime t = now.minusDays(properties.simulator().backfillDays()).truncatedTo(ChronoUnit.HOURS);
        while (!t.isAfter(now)) {
            records.add(record(device, t));
            t = t.plusMinutes(HISTORY_STEP_MINUTES);
        }
        return records;
    }

    private static ReadingRecord record(Device device, LocalDateTime t) {
        return new ReadingRecord(t, SoilSimulationModel.values(device.getId(), t));
    }
}
