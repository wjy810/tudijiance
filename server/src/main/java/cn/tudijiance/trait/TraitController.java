package cn.tudijiance.trait;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/traits")
public class TraitController {

    private static final List<String> CATEGORY_ORDER =
            List.of("PHENOLOGY", "MORPHOLOGY", "GROWTH", "STRESS", "DISEASE", "LAB", "YIELD");

    private final TraitDefMapper traitMapper;
    private final TraitOptionMapper optionMapper;

    public TraitController(TraitDefMapper traitMapper, TraitOptionMapper optionMapper) {
        this.traitMapper = traitMapper;
        this.optionMapper = optionMapper;
    }

    @GetMapping
    public List<TraitView> list() {
        Map<Long, List<TraitOption>> options = optionMapper.selectList(
                        Wrappers.<TraitOption>lambdaQuery().orderByAsc(TraitOption::getSortOrder))
                .stream().collect(Collectors.groupingBy(TraitOption::getTraitId));
        return traitMapper.selectList(null).stream()
                .sorted(Comparator.comparingInt((TraitDef t) -> CATEGORY_ORDER.indexOf(t.getCategory()))
                        .thenComparing(TraitDef::getSortOrder))
                .map(t -> new TraitView(t, options.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /** 停用的项目在录入界面里不显示。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public void setEnabled(@PathVariable Long id, @Valid @RequestBody EnabledRequest req) {
        TraitDef trait = traitMapper.selectById(id);
        if (trait == null) {
            throw ApiException.notFound("性状");
        }
        trait.setEnabled(req.enabled());
        traitMapper.updateById(trait);
    }

    public record TraitView(TraitDef trait, List<TraitOption> options) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }
}
