package cn.tudijiance.stats;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.security.Roles;
import cn.tudijiance.stats.StatsService.MultiYear;
import cn.tudijiance.stats.StatsService.Screen;
import cn.tudijiance.stats.StatsService.VarietySummary;

@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
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
