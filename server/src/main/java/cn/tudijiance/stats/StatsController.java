package cn.tudijiance.stats;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.security.Roles;
import cn.tudijiance.stats.StatsService.MultiYear;
import cn.tudijiance.stats.StatsService.Screen;
import cn.tudijiance.stats.StatsService.VarietySummary;
import cn.tudijiance.stats.TrialReportService.TrialReport;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialService;

@RestController
public class StatsController {

    private final StatsService statsService;
    private final TrialReportService reportService;
    private final TrialService trialService;

    public StatsController(StatsService statsService, TrialReportService reportService, TrialService trialService) {
        this.statsService = statsService;
        this.reportService = reportService;
        this.trialService = trialService;
    }

    /** 品种主要性状汇总表 */
    @GetMapping("/api/stats/trial-report")
    @PreAuthorize(Roles.CAN_RECORD)
    public TrialReport trialReport(@RequestParam Long trialId) {
        return reportService.report(trialId);
    }

    @GetMapping("/api/stats/trial-report/export")
    @PreAuthorize(Roles.CAN_RECORD)
    public ResponseEntity<byte[]> exportTrialReport(@RequestParam Long trialId) {
        Trial trial = trialService.get(trialId);
        return ExcelExport.response(trial.getName() + "-品种主要性状汇总表.xlsx", reportService.excel(trialId));
    }

    @GetMapping("/api/stats/variety-summary")
    @PreAuthorize(Roles.CAN_RECORD)
    public VarietySummary varietySummary(@RequestParam Long trialId, @RequestParam Long traitId) {
        return statsService.varietySummary(trialId, traitId);
    }

    @GetMapping("/api/stats/multi-year")
    @PreAuthorize(Roles.CAN_RECORD)
    public MultiYear multiYear(@RequestParam Long fieldId, @RequestParam Long traitId) {
        return statsService.multiYear(fieldId, traitId);
    }

    /** 大屏，游客也能看。 */
    @GetMapping("/api/screen")
    @PreAuthorize(Roles.CAN_VIEW_SCREEN)
    public Screen screen(@RequestParam(required = false) Long fieldId) {
        return statsService.screen(fieldId);
    }
}
