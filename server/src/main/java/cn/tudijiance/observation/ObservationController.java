package cn.tudijiance.observation;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.observation.FieldMapService.PlotDetail;
import cn.tudijiance.observation.FieldMapService.TrialStatus;
import cn.tudijiance.observation.ObservationDtos.AbnormalRequest;
import cn.tudijiance.observation.ObservationDtos.EntryRequest;
import cn.tudijiance.observation.ObservationDtos.HistoryView;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.observation.ObservationDtos.PageResult;
import cn.tudijiance.observation.ObservationDtos.UpdateRequest;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 录入数据、查看、修改、删除；平面图的小区状态和小区详情。 */
@RestController
public class ObservationController {

    private final ObservationService observationService;
    private final FieldMapService fieldMapService;

    public ObservationController(ObservationService observationService, FieldMapService fieldMapService) {
        this.observationService = observationService;
        this.fieldMapService = fieldMapService;
    }

    @PostMapping("/api/observations")
    @PreAuthorize(Roles.CAN_RECORD)
    public List<ObservationView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EntryRequest req) {
        return observationService.create(req, CurrentUser.id(jwt));
    }

    @GetMapping("/api/observations")
    @PreAuthorize(Roles.CAN_RECORD)
    public PageResult<ObservationView> list(
            @RequestParam Long trialId,
            @RequestParam(required = false) Long varietyId,
            @RequestParam(required = false) Long plotId,
            @RequestParam(required = false) Long traitId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long recordedBy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return observationService.list(trialId, varietyId, plotId, traitId, category, from, to, recordedBy, page, size);
    }

    @PutMapping("/api/observations/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public ObservationView update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @Valid @RequestBody UpdateRequest req) {
        return observationService.update(id, req, CurrentUser.id(jwt));
    }

    @DeleteMapping("/api/observations/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        observationService.delete(id, CurrentUser.id(jwt));
    }

    @GetMapping("/api/observations/{id}/history")
    @PreAuthorize(Roles.CAN_RECORD)
    public List<HistoryView> history(@PathVariable Long id) {
        return observationService.history(id);
    }

    @GetMapping("/api/trials/{id}/status")
    @PreAuthorize(Roles.CAN_RECORD)
    public TrialStatus status(@PathVariable Long id) {
        return fieldMapService.status(id);
    }

    @GetMapping("/api/trials/{id}/plots/{plotId}")
    @PreAuthorize(Roles.CAN_RECORD)
    public PlotDetail plotDetail(@PathVariable Long id, @PathVariable Long plotId) {
        return fieldMapService.plotDetail(id, plotId);
    }

    @GetMapping("/api/trials/{id}/trait-values")
    @PreAuthorize(Roles.CAN_RECORD)
    public List<FieldMapService.PlotValue> traitValues(@PathVariable Long id, @RequestParam Long traitId) {
        return fieldMapService.traitValues(id, traitId);
    }

    @PutMapping("/api/trials/{id}/abnormal")
    @PreAuthorize(Roles.CAN_RECORD)
    public TrialStatus setAbnormal(@PathVariable Long id, @Valid @RequestBody AbnormalRequest req) {
        observationService.setAbnormal(id, req);
        return fieldMapService.status(id);
    }
}
