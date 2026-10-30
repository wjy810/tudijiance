package cn.tudijiance.device;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.device.DeviceDtos.Bucket;
import cn.tudijiance.device.DeviceDtos.DeviceRequest;
import cn.tudijiance.device.DeviceDtos.DeviceView;
import cn.tudijiance.device.DeviceDtos.DeviceWithToken;
import cn.tudijiance.device.DeviceDtos.LatestValue;
import cn.tudijiance.device.DeviceDtos.Series;
import cn.tudijiance.device.DeviceDtos.SeriesPoint;
import cn.tudijiance.field.FieldService;

@Service
public class DeviceService {

    /** 设备上报的 MQTT 主题，{code} 是设备编号。 */
    public static final String TOPIC_TEMPLATE = "tudi/devices/%s/up";

    private static final Set<String> DEVICE_TYPES = Set.of(Device.TYPE_SOIL_3IN1);
    private static final int MAX_RAW_POINTS = 10_000;

    private final DeviceMapper deviceMapper;
    private final SensorMetricMapper metricMapper;
    private final SensorReadingMapper readingMapper;
    private final FieldService fieldService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(DeviceMapper deviceMapper, SensorMetricMapper metricMapper,
                         SensorReadingMapper readingMapper, FieldService fieldService,
                         ApplicationEventPublisher events, Clock clock) {
        this.deviceMapper = deviceMapper;
        this.metricMapper = metricMapper;
        this.readingMapper = readingMapper;
        this.fieldService = fieldService;
        this.events = events;
        this.clock = clock;
    }

    public List<DeviceView> list(Long fieldId) {
        var query = Wrappers.<Device>lambdaQuery().orderByAsc(Device::getCode);
        if (fieldId != null) {
            query.eq(Device::getFieldId, fieldId);
        }
        Map<Long, Map<String, LatestValue>> latest = latestValues();
        return deviceMapper.selectList(query).stream()
                .map(d -> view(d, latest.getOrDefault(d.getId(), Map.of())))
                .toList();
    }

    public Device get(Long id) {
        Device device = deviceMapper.selectById(id);
        if (device == null) {
            throw ApiException.notFound("设备");
        }
        return device;
    }

    public Device findByCode(String code) {
        return deviceMapper.selectOne(Wrappers.<Device>lambdaQuery().eq(Device::getCode, code));
    }

    public List<Device> simulatedDevices() {
        return deviceMapper.selectList(Wrappers.<Device>lambdaQuery().eq(Device::getSimulated, true));
    }

    public DeviceWithToken create(DeviceRequest req) {
        if (findByCode(req.code()) != null) {
            throw ApiException.conflict("设备编号已存在：" + req.code());
        }
        Device device = new Device();
        apply(device, req);
        String token = newToken();
        device.setTokenHash(hash(token));
        device.setStatus(Device.STATUS_NEVER);
        device.setCreatedAt(LocalDateTime.now(clock));
        deviceMapper.insert(device);
        events.publishEvent(new DeviceChangedEvent(device.getId()));
        return new DeviceWithToken(view(device, Map.of()), token);
    }

    public DeviceView update(Long id, DeviceRequest req) {
        Device device = get(id);
        if (!device.getCode().equals(req.code()) && findByCode(req.code()) != null) {
            throw ApiException.conflict("设备编号已存在：" + req.code());
        }
        apply(device, req);
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .set(Device::getCode, device.getCode())
                .set(Device::getName, device.getName())
                .set(Device::getDeviceType, device.getDeviceType())
                .set(Device::getFieldId, device.getFieldId())
                .set(Device::getPosRow, device.getPosRow())
                .set(Device::getPosCol, device.getPosCol())
                .set(Device::getDepthCm, device.getDepthCm())
                .set(Device::getSimulated, device.getSimulated())
                .set(Device::getRemark, device.getRemark())
                .eq(Device::getId, id));
        events.publishEvent(new DeviceChangedEvent(id));
        return view(get(id), latestValues().getOrDefault(id, Map.of()));
    }

    public void delete(Long id) {
        get(id);
        deviceMapper.deleteById(id);
    }

    public DeviceWithToken regenerateToken(Long id) {
        Device device = get(id);
        String token = newToken();
        device.setTokenHash(hash(token));
        deviceMapper.updateById(device);
        return new DeviceWithToken(view(device, latestValues().getOrDefault(id, Map.of())), token);
    }

    /** 校验 HTTP 上报的设备令牌，通过时返回设备。 */
    public Device authenticate(String code, String token) {
        Device device = code == null ? null : findByCode(code);
        if (device == null || token == null || device.getTokenHash() == null
                || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                device.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.unauthorized("设备编号或令牌不正确");
        }
        return device;
    }

    public List<SensorMetric> metrics() {
        return metricMapper.selectList(Wrappers.<SensorMetric>lambdaQuery().orderByAsc(SensorMetric::getSortOrder));
    }

    /** 查询一台设备某个测量项的曲线数据。bucket 为 HOUR 或 DAY 时按小时或天求平均、最小、最大。 */
    public Series readings(Long deviceId, String metricCode, LocalDateTime from, LocalDateTime to, Bucket bucket) {
        get(deviceId);
        SensorMetric metric = metricMapper.selectById(metricCode);
        if (metric == null) {
            throw ApiException.notFound("测量项 " + metricCode);
        }
        LocalDateTime end = to != null ? to : LocalDateTime.now(clock);
        LocalDateTime start = from != null ? from : end.minusDays(7);
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("开始时间要早于结束时间");
        }
        List<SensorReading> rows = readingMapper.selectList(Wrappers.<SensorReading>lambdaQuery()
                .eq(SensorReading::getDeviceId, deviceId)
                .eq(SensorReading::getMetric, metricCode)
                .ge(SensorReading::getMeasuredAt, start)
                .le(SensorReading::getMeasuredAt, end)
                .orderByAsc(SensorReading::getMeasuredAt)
                .last("LIMIT " + MAX_RAW_POINTS));

        List<SeriesPoint> points;
        if (bucket == Bucket.RAW) {
            points = rows.stream().map(r -> new SeriesPoint(r.getMeasuredAt(), r.getMetricValue(), null, null)).toList();
        } else {
            ChronoUnit unit = bucket == Bucket.HOUR ? ChronoUnit.HOURS : ChronoUnit.DAYS;
            TreeMap<LocalDateTime, List<BigDecimal>> groups = new TreeMap<>();
            for (SensorReading r : rows) {
                groups.computeIfAbsent(r.getMeasuredAt().truncatedTo(unit), k -> new ArrayList<>()).add(r.getMetricValue());
            }
            int scale = metric.getPrecisionDigits() == null ? 2 : metric.getPrecisionDigits();
            points = new ArrayList<>();
            groups.forEach((t, values) -> {
                BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal avg = sum.divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
                BigDecimal min = values.stream().min(BigDecimal::compareTo).orElseThrow();
                BigDecimal max = values.stream().max(BigDecimal::compareTo).orElseThrow();
                points.add(new SeriesPoint(t, avg, min, max));
            });
        }
        return new Series(metricCode, metric.getUnit(), bucket, points);
    }

    private Map<Long, Map<String, LatestValue>> latestValues() {
        Map<Long, Map<String, LatestValue>> result = new HashMap<>();
        for (SensorReading r : readingMapper.selectLatest()) {
            result.computeIfAbsent(r.getDeviceId(), k -> new TreeMap<>())
                    .put(r.getMetric(), new LatestValue(r.getMetricValue(), r.getMeasuredAt(),
                            Boolean.TRUE.equals(r.getOutOfRange())));
        }
        return result;
    }

    private void apply(Device device, DeviceRequest req) {
        if (!DEVICE_TYPES.contains(req.deviceType())) {
            throw ApiException.badRequest("设备类型的取值不正确：" + req.deviceType());
        }
        if (req.fieldId() != null) {
            fieldService.get(req.fieldId());
        }
        device.setCode(req.code().trim());
        device.setName(req.name().trim());
        device.setDeviceType(req.deviceType());
        device.setFieldId(req.fieldId());
        device.setPosRow(req.posRow());
        device.setPosCol(req.posCol());
        device.setDepthCm(req.depthCm());
        device.setSimulated(Boolean.TRUE.equals(req.simulated()));
        device.setRemark(req.remark());
    }

    private static DeviceView view(Device d, Map<String, LatestValue> latest) {
        return new DeviceView(d.getId(), d.getCode(), d.getName(), d.getDeviceType(), d.getFieldId(),
                d.getPosRow(), d.getPosCol(), d.getDepthCm(), Boolean.TRUE.equals(d.getSimulated()),
                d.getStatus(), d.getLastSeenAt(), d.getRemark(),
                TOPIC_TEMPLATE.formatted(d.getCode()), latest);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
