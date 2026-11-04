package cn.tudijiance.correction;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.correction.CorrectionService.CorrectionView;
import cn.tudijiance.correction.CorrectionService.CreateRequest;
import cn.tudijiance.correction.CorrectionService.ReviewRequest;
import cn.tudijiance.correction.CorrectionService.Summary;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 更正申请：学生提交，老师通过或驳回 */
@RestController
@RequestMapping("/api/corrections")
public class CorrectionController {

    private final CorrectionService service;

    public CorrectionController(CorrectionService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public CorrectionView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest req) {
        return service.create(req, CurrentUser.id(jwt));
    }

    /** 老师看全部，学生只看自己提交的 */
    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public List<CorrectionView> list(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Long trialId) {
        return service.list(CurrentUser.id(jwt), isTeacher(jwt), status, trialId);
    }

    @GetMapping("/summary")
    @PreAuthorize(Roles.CAN_RECORD)
    public Summary summary(@AuthenticationPrincipal Jwt jwt) {
        return service.summary(CurrentUser.id(jwt), isTeacher(jwt));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.IS_TEACHER)
    public CorrectionView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @Valid @RequestBody(required = false) ReviewRequest req) {
        return service.approve(id, CurrentUser.id(jwt), req == null ? null : req.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.IS_TEACHER)
    public CorrectionView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) ReviewRequest req) {
        return service.reject(id, CurrentUser.id(jwt), req == null ? null : req.note());
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize(Roles.CAN_RECORD)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.withdraw(id, CurrentUser.id(jwt));
    }

    private static boolean isTeacher(Jwt jwt) {
        return Roles.TEACHER.equals(CurrentUser.role(jwt));
    }
}
