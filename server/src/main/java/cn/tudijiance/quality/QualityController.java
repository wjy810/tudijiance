package cn.tudijiance.quality;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.quality.QualityService.QualityReport;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;

@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityService quality;

    public QualityController(QualityService quality) {
        this.quality = quality;
    }

    public record AckRequest(Long trialId, String key, String note) {
    }

    /** 检查一个试验的数据 */
    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public QualityReport check(@RequestParam Long trialId) {
        return quality.check(trialId);
    }

    /** 老师核实过，确认没错 */
    @PostMapping("/ack")
    @PreAuthorize(Roles.IS_TEACHER)
    public void ack(@AuthenticationPrincipal Jwt jwt, @RequestBody AckRequest req) {
        quality.ack(req.trialId(), req.key(), req.note(), CurrentUser.id(jwt));
    }

    @DeleteMapping("/ack")
    @PreAuthorize(Roles.IS_TEACHER)
    public void unack(@RequestParam Long trialId, @RequestParam String key) {
        quality.unack(trialId, key);
    }
}
