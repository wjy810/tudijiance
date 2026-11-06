package cn.tudijiance.breeding;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.breeding.BreedingDtos.LandRequest;
import cn.tudijiance.breeding.BreedingDtos.LineRow;
import cn.tudijiance.breeding.BreedingDtos.SeasonRequest;
import cn.tudijiance.breeding.BreedingDtos.SeasonView;
import cn.tudijiance.field.FieldDtos.FieldDetail;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 季和地块 */
@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final SeasonService seasonService;
    private final LineService lineService;

    public SeasonController(SeasonService seasonService, LineService lineService) {
        this.seasonService = seasonService;
        this.lineService = lineService;
    }

    /** 系统管理员登记设备时也要看地块属于哪一季 */
    @GetMapping
    @PreAuthorize(Roles.CAN_VIEW_SETUP)
    public List<SeasonView> list() {
        return seasonService.list();
    }

    @PostMapping
    @PreAuthorize(Roles.IS_TEACHER)
    public SeasonView create(@Valid @RequestBody SeasonRequest req) {
        return seasonService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public SeasonView update(@PathVariable Long id, @Valid @RequestBody SeasonRequest req) {
        return seasonService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void delete(@PathVariable Long id) {
        seasonService.delete(id);
    }

    /** 在季里新建地块 */
    @PostMapping("/{id}/fields")
    @PreAuthorize(Roles.IS_TEACHER)
    public FieldDetail createLand(@PathVariable Long id, @Valid @RequestBody LandRequest req) {
        return seasonService.createLand(id, req);
    }

    @DeleteMapping("/{id}/fields/{fieldId}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void deleteLand(@PathVariable Long id, @PathVariable Long fieldId) {
        seasonService.deleteLand(id, fieldId);
    }

    /** 这一季布置好以后，接着的季里没再种、也没有子代的品系，给老师勾选淘汰还是保留 */
    @GetMapping("/{id}/cleanup")
    @PreAuthorize(Roles.CAN_RECORD)
    public List<LineRow> cleanup(@PathVariable Long id) {
        return lineService.cleanup(id);
    }
}
