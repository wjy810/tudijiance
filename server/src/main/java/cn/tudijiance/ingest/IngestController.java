package cn.tudijiance.ingest;

import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceService;
import cn.tudijiance.device.SensorReading;
import cn.tudijiance.ingest.SensorIngestService.IngestResult;

/**
 * HTTP 上报接口（硬件预留）。适合支持 HTTP 的 DTU，也方便用 curl 或脚本调试：
 *
 * <pre>
 * POST /api/ingest/devices/{code}/readings
 * X-Device-Token: 新建设备时显示的令牌
 * Content-Type: application/json
 *
 * {"ts": 1730275200, "values": {"soil_temp": 12.3, "soil_moisture": 23.5, "soil_ph": 7.12}}
 * </pre>
 */
@RestController
public class IngestController {

    private final DeviceService deviceService;
    private final PayloadDecoder decoder;
    private final SensorIngestService ingestService;

    public IngestController(DeviceService deviceService, PayloadDecoder decoder, SensorIngestService ingestService) {
        this.deviceService = deviceService;
        this.decoder = decoder;
        this.ingestService = ingestService;
    }

    @PostMapping("/api/ingest/devices/{code}/readings")
    public IngestResult ingest(@PathVariable String code,
                               @RequestHeader("X-Device-Token") String token,
                               @RequestBody byte[] body) {
        Device device = deviceService.authenticate(code, token);
        List<ReadingRecord> records = decoder.decode(body);
        return ingestService.ingest(device, records, SensorReading.SOURCE_HTTP);
    }
}
