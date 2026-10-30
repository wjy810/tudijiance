package cn.tudijiance.device;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.device.DeviceDtos.Bucket;
import cn.tudijiance.device.DeviceDtos.DeviceRequest;
import cn.tudijiance.device.DeviceDtos.DeviceView;
import cn.tudijiance.device.DeviceDtos.DeviceWithToken;
import cn.tudijiance.device.DeviceDtos.Series;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/sensor-metrics")
    @PreAuthorize(Roles.CAN_VIEW_SETUP)
    public List<SensorMetric> metrics() {
        return deviceService.metrics();
    }

    @GetMapping("/devices")
    @PreAuthorize(Roles.CAN_VIEW_SETUP)
    public List<DeviceView> list(@RequestParam(required = false) Long fieldId) {
        return deviceService.list(fieldId);
    }

    @PostMapping("/devices")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public DeviceWithToken create(@Valid @RequestBody DeviceRequest req) {
        return deviceService.create(req);
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public DeviceView update(@PathVariable Long id, @Valid @RequestBody DeviceRequest req) {
        return deviceService.update(id, req);
    }

    @DeleteMapping("/devices/{id}")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public void delete(@PathVariable Long id) {
        deviceService.delete(id);
    }

    @PostMapping("/devices/{id}/token")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public DeviceWithToken regenerateToken(@PathVariable Long id) {
        return deviceService.regenerateToken(id);
    }

    @GetMapping("/devices/{id}/readings")
    @PreAuthorize(Roles.CAN_VIEW_SETUP)
    public Series readings(@PathVariable Long id,
                           @RequestParam String metric,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                           @RequestParam(defaultValue = "HOUR") Bucket bucket) {
        return deviceService.readings(id, metric, from, to, bucket);
    }
}
