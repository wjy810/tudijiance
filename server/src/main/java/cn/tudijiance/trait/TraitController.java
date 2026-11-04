package cn.tudijiance.trait;

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

import cn.tudijiance.security.Roles;
import cn.tudijiance.trait.TraitDtos.AliasRequest;
import cn.tudijiance.trait.TraitDtos.CustomTraitRequest;
import cn.tudijiance.trait.TraitDtos.EnabledRequest;
import cn.tudijiance.trait.TraitDtos.ImportantRequest;
import cn.tudijiance.trait.TraitDtos.TraitView;
import jakarta.validation.Valid;

/** 录入项目：标准项目和自定义项目，以及导入表格时用的表头别名。 */
@RestController
@RequestMapping("/api/traits")
public class TraitController {

    private final TraitService traitService;

    public TraitController(TraitService traitService) {
        this.traitService = traitService;
    }

    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public List<TraitView> list() {
        return traitService.list();
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize(Roles.IS_TEACHER)
    public void setEnabled(@PathVariable Long id, @Valid @RequestBody EnabledRequest req) {
        traitService.setEnabled(id, req.enabled());
    }

    @PutMapping("/{id}/important")
    @PreAuthorize(Roles.IS_TEACHER)
    public void setImportant(@PathVariable Long id, @Valid @RequestBody ImportantRequest req) {
        traitService.setImportant(id, req.important());
    }

    @PostMapping
    @PreAuthorize(Roles.IS_TEACHER)
    public TraitView createCustom(@Valid @RequestBody CustomTraitRequest req) {
        return traitService.createCustom(req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void deleteCustom(@PathVariable Long id) {
        traitService.deleteCustom(id);
    }

    /** 替换这个项目的全部表头别名 */
    @PutMapping("/{id}/aliases")
    @PreAuthorize(Roles.IS_TEACHER)
    public List<String> setAliases(@PathVariable Long id, @Valid @RequestBody AliasRequest req) {
        return traitService.setAliases(id, req.aliases());
    }
}
