package cn.tudijiance.breeding;

import java.util.ArrayList;
import java.util.List;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.breeding.BreedingDtos.AdoptRequest;
import cn.tudijiance.breeding.BreedingDtos.CrossRequest;
import cn.tudijiance.breeding.BreedingDtos.CrossResult;
import cn.tudijiance.breeding.BreedingDtos.FoundersRequest;
import cn.tudijiance.breeding.BreedingDtos.GraphView;
import cn.tudijiance.breeding.BreedingDtos.LineDetail;
import cn.tudijiance.breeding.BreedingDtos.LineRow;
import cn.tudijiance.breeding.BreedingDtos.LineUpdateRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantValueView;
import cn.tudijiance.breeding.BreedingDtos.PlantValuesRequest;
import cn.tudijiance.breeding.BreedingDtos.SelectionRequest;
import cn.tudijiance.breeding.BreedingDtos.StatusRequest;
import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 品系库、系谱图、杂交、选株 */
@RestController
@RequestMapping("/api/lines")
public class LineController {

    private final LineService lineService;
    private final LineDetailService detailService;

    public LineController(LineService lineService, LineDetailService detailService) {
        this.lineService = lineService;
        this.detailService = detailService;
    }

    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public List<LineRow> list(@RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String kind) {
        return lineService.list(keyword, status, kind);
    }

    @GetMapping("/graph")
    @PreAuthorize(Roles.CAN_RECORD)
    public GraphView graph() {
        return lineService.graph();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.CAN_RECORD)
    public LineDetail detail(@PathVariable Long id) {
        return detailService.detail(id);
    }

    /** 导出品系各季的数据：每个项目一行，每季的平均值和各位置的值 */
    @GetMapping("/{id}/export")
    @PreAuthorize(Roles.CAN_RECORD)
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        LineDetail d = detailService.detail(id);
        List<List<Object>> rows = new ArrayList<>();
        d.plantValues().forEach(v -> rows.add(List.of("选出时的单株数据", v.traitName(), v.display(), "", "")));
        d.seasonValues().forEach(s -> s.traits().forEach(t -> rows.add(List.of(
                s.seasonName() + (s.generation() == null ? "" : "（F" + s.generation() + "）"),
                t.traitName(), t.display(), t.count(),
                String.join("；", t.plots().stream().map(p -> p.place() + "：" + p.display()).toList())))));
        String title = d.line().name() + (d.line().name().equals(d.line().code()) ? "" : "（" + d.line().code() + "）");
        byte[] body = ExcelExport.write("品系数据", List.of(title + " 各季数据",
                        "系谱式：" + d.line().pedigree() + "　来源：" + d.originText()),
                List.of("季", "项目", "值（多个位置取平均）", "位置数", "各位置的值"), rows);
        return ExcelExport.response(d.line().code() + "-品系数据.xlsx", body);
    }

    @PostMapping("/founders")
    @PreAuthorize(Roles.IS_TEACHER)
    public List<LineRow> createFounders(@Valid @RequestBody FoundersRequest req) {
        return lineService.createFounders(req);
    }

    @PostMapping("/adopt")
    @PreAuthorize(Roles.IS_TEACHER)
    public List<LineRow> adopt(@Valid @RequestBody AdoptRequest req) {
        return lineService.adopt(req);
    }

    @PostMapping("/crosses")
    @PreAuthorize(Roles.IS_TEACHER)
    public CrossResult cross(@Valid @RequestBody CrossRequest req) {
        return lineService.cross(req);
    }

    @PostMapping("/{id}/selections")
    @PreAuthorize(Roles.IS_TEACHER)
    public List<LineRow> select(@PathVariable Long id, @Valid @RequestBody SelectionRequest req) {
        return lineService.select(id, req);
    }

    @PutMapping("/status")
    @PreAuthorize(Roles.IS_TEACHER)
    public List<LineRow> setStatus(@Valid @RequestBody StatusRequest req) {
        return lineService.setStatus(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public LineRow update(@PathVariable Long id, @Valid @RequestBody LineUpdateRequest req) {
        return lineService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void delete(@PathVariable Long id) {
        lineService.delete(id);
    }

    /** 选出时的单株数据（老师、学生都能录） */
    @PutMapping("/{id}/plant-values")
    @PreAuthorize(Roles.CAN_RECORD)
    public List<PlantValueView> savePlantValues(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                @Valid @RequestBody PlantValuesRequest req) {
        return detailService.savePlantValues(id, req, CurrentUser.id(jwt));
    }
}
