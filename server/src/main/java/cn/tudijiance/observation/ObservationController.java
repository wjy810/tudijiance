package cn.tudijiance.observation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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

import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.observation.FieldMapService.PlotDetail;
import cn.tudijiance.observation.FieldMapService.TrialStatus;
import cn.tudijiance.observation.ObservationDtos.AbnormalRequest;
import cn.tudijiance.observation.ObservationDtos.EntryRequest;
import cn.tudijiance.observation.ObservationDtos.HistoryView;
import cn.tudijiance.observation.ObservationDtos.ObservationFilter;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.observation.ObservationDtos.PageResult;
import cn.tudijiance.observation.ObservationDtos.UpdateRequest;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialService;
import jakarta.validation.Valid;

/** 录入数据、查看、修改、删除；平面图的小区状态和小区详情。 */
@RestController
public class ObservationController {

    private static final Map<String, String> CATEGORY = Map.of("OPERATION", "农事操作", "PHENOLOGY", "物候期",
            "MORPHOLOGY", "形态特征", "GROWTH", "生育动态", "STRESS", "抗逆性", "DISEASE", "病虫害", "LAB", "室内考种",
            "YIELD", "产量", "CUSTOM", "自定义");
    private static final Map<String, String> SOURCE = Map.of("MANUAL", "手动录入", "IMPORT", "表格导入", "SENSOR", "自动采集");

    private final ObservationService observationService;
    private final FieldMapService fieldMapService;
    private final TrialService trialService;
    private final Clock clock;

    public ObservationController(ObservationService observationService, FieldMapService fieldMapService,
                                 TrialService trialService, Clock clock) {
        this.observationService = observationService;
        this.fieldMapService = fieldMapService;
        this.trialService = trialService;
        this.clock = clock;
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
        return observationService.list(new ObservationFilter(trialId, varietyId, plotId, traitId, category, from, to,
                recordedBy), page, size);
    }

    /** 按筛选条件导出 Excel，条件和查看时一样。 */
    @GetMapping("/api/observations/export")
    @PreAuthorize(Roles.CAN_RECORD)
    public ResponseEntity<byte[]> export(
            @RequestParam Long trialId,
            @RequestParam(required = false) Long varietyId,
            @RequestParam(required = false) Long plotId,
            @RequestParam(required = false) Long traitId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long recordedBy) {
        Trial trial = trialService.get(trialId);
        List<ObservationView> list = observationService.listForExport(new ObservationFilter(trialId, varietyId, plotId,
                traitId, category, from, to, recordedBy));
        List<String> headers = List.of("调查日期", "小区", "品种", "重复", "分类", "项目", "值", "数值", "单位", "备注",
                "来源", "录入人", "录入时间", "修改人", "修改时间");
        List<List<Object>> rows = list.stream().map(o -> {
            List<Object> row = new ArrayList<>();
            row.add(o.observedOn());
            row.add(o.plotCode());
            row.add(o.varietyName());
            row.add(o.replicateNo());
            row.add(CATEGORY.getOrDefault(o.traitCategory(), o.traitCategory()));
            row.add(o.traitName());
            row.add(o.displayValue());
            row.add(o.valueNum());
            row.add(o.unit());
            row.add(o.note());
            row.add(SOURCE.getOrDefault(o.source(), o.source()));
            row.add(o.recordedBy());
            row.add(o.recordedAt());
            row.add(o.updatedBy());
            row.add(o.updatedAt());
            return row;
        }).toList();
        byte[] body = ExcelExport.write("数据", List.of(trial.getName() + " 录入数据",
                "共 " + list.size() + " 条 · 导出时间 " + LocalDateTime.now(clock).withSecond(0).withNano(0)
                        .toString().replace('T', ' ')), headers, rows);
        return ExcelExport.response(trial.getName() + "-数据.xlsx", body);
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
