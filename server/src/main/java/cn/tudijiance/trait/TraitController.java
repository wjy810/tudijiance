package cn.tudijiance.trait;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 录入项目：标准项目（NY/T 1301—2025）和老师自己加的自定义项目。 */
@RestController
@RequestMapping("/api/traits")
public class TraitController {

    private static final List<String> CATEGORY_ORDER =
            List.of("OPERATION", "PHENOLOGY", "MORPHOLOGY", "GROWTH", "STRESS", "DISEASE", "LAB", "YIELD", "CUSTOM");
    private static final Set<String> CUSTOM_TYPES = Set.of("DATE", "NUMBER", "OPTION", "TEXT");

    private final TraitDefMapper traitMapper;
    private final TraitOptionMapper optionMapper;
    private final ObservationMapper observationMapper;
    private final TraitCatalog catalog;
    private final Clock clock;

    public TraitController(TraitDefMapper traitMapper, TraitOptionMapper optionMapper,
                           ObservationMapper observationMapper, TraitCatalog catalog, Clock clock) {
        this.traitMapper = traitMapper;
        this.optionMapper = optionMapper;
        this.observationMapper = observationMapper;
        this.catalog = catalog;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize(Roles.CAN_RECORD)
    public List<TraitView> list() {
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        return traitMapper.selectList(null).stream()
                .sorted(Comparator.comparingInt((TraitDef t) -> CATEGORY_ORDER.indexOf(t.getCategory()))
                        .thenComparing(TraitDef::getSortOrder))
                .map(t -> new TraitView(t, options.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /** 停用的项目在录入界面里不显示。 */
    @PutMapping("/{id}/enabled")
    @PreAuthorize(Roles.IS_TEACHER)
    public void setEnabled(@PathVariable Long id, @Valid @RequestBody EnabledRequest req) {
        TraitDef trait = catalog.get(id);
        trait.setEnabled(req.enabled());
        traitMapper.updateById(trait);
    }

    /** 新增自定义项目，例如"小区负责人""田间积水"。 */
    @PostMapping
    @PreAuthorize(Roles.IS_TEACHER)
    @Transactional
    public TraitView createCustom(@Valid @RequestBody CustomTraitRequest req) {
        if (!CUSTOM_TYPES.contains(req.dataType())) {
            throw ApiException.badRequest("自定义项目的类型只能是日期、数值、选项或文字");
        }
        List<String> options = req.options() == null ? List.of()
                : req.options().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if ("OPTION".equals(req.dataType()) && options.size() < 2) {
            throw ApiException.badRequest("选项类型至少要填 2 个选项");
        }
        if (options.stream().anyMatch(o -> o.length() > 16)) {
            throw ApiException.badRequest("每个选项不能超过 16 个字");
        }
        Set<String> names = new HashSet<>(traitMapper.selectList(null).stream().map(TraitDef::getName).toList());
        if (names.contains(req.name().trim())) {
            throw ApiException.conflict("已经有同名的项目：" + req.name().trim());
        }
        TraitDef trait = new TraitDef();
        trait.setCode("custom_" + LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        trait.setName(req.name().trim());
        trait.setCategory("CUSTOM");
        trait.setDataType(req.dataType());
        trait.setUnit(req.unit() == null || req.unit().isBlank() ? null : req.unit().trim());
        trait.setPrecisionDigits("NUMBER".equals(req.dataType()) ? req.precisionDigits() : null);
        trait.setAggMethod("NUMBER".equals(req.dataType()) ? "AVG" : "NONE");
        trait.setSurveyTime(req.description());
        trait.setIsStandard(false);
        trait.setIsComputed(false);
        trait.setEnabled(true);
        trait.setSortOrder((int) (traitMapper.selectCount(Wrappers.<TraitDef>lambdaQuery()
                .eq(TraitDef::getCategory, "CUSTOM")) + 1) * 10);
        traitMapper.insert(trait);
        if ("OPTION".equals(req.dataType())) {
            int order = 0;
            for (String o : options) {
                TraitOption option = new TraitOption();
                option.setTraitId(trait.getId());
                option.setValueCode(o);
                option.setLabel(o);
                option.setSortOrder(++order);
                optionMapper.insert(option);
            }
        }
        return new TraitView(trait, catalog.optionsOf(trait.getId()));
    }

    /** 只能删除还没有录过数据的自定义项目；标准项目只能停用。 */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.IS_TEACHER)
    public void deleteCustom(@PathVariable Long id) {
        TraitDef trait = catalog.get(id);
        if (Boolean.TRUE.equals(trait.getIsStandard())) {
            throw ApiException.badRequest("标准项目不能删除，可以停用");
        }
        long used = observationMapper.selectCount(Wrappers.<Observation>lambdaQuery().eq(Observation::getTraitId, id));
        if (used > 0) {
            throw ApiException.conflict("这个项目已经录过数据，不能删除，可以停用");
        }
        traitMapper.deleteById(id);
    }

    public record TraitView(TraitDef trait, List<TraitOption> options) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }

    public record CustomTraitRequest(
            @NotBlank @Size(max = 50) String name,
            @NotBlank String dataType,
            @Size(max = 16) String unit,
            @Min(0) @Max(4) Integer precisionDigits,
            List<String> options,
            @Size(max = 200) String description) {
    }
}
