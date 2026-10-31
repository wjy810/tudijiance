package cn.tudijiance.variety;

import java.util.List;

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

import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/varieties")
public class VarietyController {

    private final VarietyService varietyService;

    public VarietyController(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public List<Variety> list(@RequestParam(required = false) String keyword) {
        return varietyService.list(keyword);
    }

    @PostMapping
    @PreAuthorize(Roles.IS_TEACHER)
    public Variety create(@Valid @RequestBody VarietyRequest req) {
        return varietyService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public Variety update(@PathVariable Long id, @Valid @RequestBody VarietyRequest req) {
        return varietyService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void delete(@PathVariable Long id) {
        varietyService.delete(id);
    }

    public record VarietyRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String code,
            @Size(max = 200) String breeder,
            String ecotype,
            String qualityType,
            String controlType,
            @Size(max = 500) String remark) {
    }
}
