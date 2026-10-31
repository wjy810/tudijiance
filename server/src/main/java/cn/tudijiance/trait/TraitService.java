package cn.tudijiance.trait;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.trait.TraitDtos.CustomTraitRequest;
import cn.tudijiance.trait.TraitDtos.TraitView;

/** 录入项目：标准项目（NY/T 1301—2025）、老师自己加的自定义项目，以及导入表格时用的表头别名。 */
@Service
public class TraitService {

    private static final List<String> CATEGORY_ORDER =
            List.of("OPERATION", "PHENOLOGY", "MORPHOLOGY", "GROWTH", "STRESS", "DISEASE", "LAB", "YIELD", "CUSTOM");
    private static final Set<String> CUSTOM_TYPES = Set.of("DATE", "NUMBER", "OPTION", "TEXT");
    private static final int MAX_OPTION_LENGTH = 16;

    private final TraitDefMapper traitMapper;
    private final TraitOptionMapper optionMapper;
    private final TraitAliasMapper aliasMapper;
    private final ObservationMapper observationMapper;
    private final TraitCatalog catalog;
    private final Clock clock;

    public TraitService(TraitDefMapper traitMapper, TraitOptionMapper optionMapper, TraitAliasMapper aliasMapper,
                        ObservationMapper observationMapper, TraitCatalog catalog, Clock clock) {
        this.traitMapper = traitMapper;
        this.optionMapper = optionMapper;
        this.aliasMapper = aliasMapper;
        this.observationMapper = observationMapper;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** 按分类、分类内的顺序排好 */
    public List<TraitView> list() {
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        Map<Long, List<String>> aliases = aliasesByTrait();
        return traitMapper.selectList(null).stream()
                .sorted(Comparator.comparingInt((TraitDef t) -> CATEGORY_ORDER.indexOf(t.getCategory()))
                        .thenComparing(TraitDef::getSortOrder))
                .map(t -> new TraitView(t, options.getOrDefault(t.getId(), List.of()),
                        aliases.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /** 停用的项目在录入界面里不显示 */
    public void setEnabled(Long id, boolean enabled) {
        TraitDef trait = catalog.get(id);
        trait.setEnabled(enabled);
        traitMapper.updateById(trait);
    }

    /** 新增自定义项目，例如"小区负责人""田间积水" */
    @Transactional
    public TraitView createCustom(CustomTraitRequest req) {
        if (!CUSTOM_TYPES.contains(req.dataType())) {
            throw ApiException.badRequest("自定义项目的类型只能是日期、数值、选项或文字");
        }
        String name = req.name().trim();
        List<String> options = req.options() == null ? List.of()
                : req.options().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if ("OPTION".equals(req.dataType()) && options.size() < 2) {
            throw ApiException.badRequest("选项类型至少要填 2 个选项");
        }
        if (options.stream().anyMatch(o -> o.length() > MAX_OPTION_LENGTH)) {
            throw ApiException.badRequest("每个选项不能超过 " + MAX_OPTION_LENGTH + " 个字");
        }
        if (traitMapper.selectCount(Wrappers.<TraitDef>lambdaQuery().eq(TraitDef::getName, name)) > 0) {
            throw ApiException.conflict("已经有同名的项目：" + name);
        }
        boolean number = "NUMBER".equals(req.dataType());
        TraitDef trait = new TraitDef();
        trait.setCode("custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        trait.setName(name);
        trait.setCategory("CUSTOM");
        trait.setDataType(req.dataType());
        trait.setUnit(req.unit() == null || req.unit().isBlank() ? null : req.unit().trim());
        trait.setPrecisionDigits(number ? req.precisionDigits() : null);
        trait.setAggMethod(number ? "AVG" : "OPTION".equals(req.dataType()) ? "MODE" : "NONE");
        trait.setSurveyTime(req.description());
        trait.setIsStandard(false);
        trait.setIsComputed(false);
        trait.setEnabled(true);
        long customCount = traitMapper.selectCount(Wrappers.<TraitDef>lambdaQuery().eq(TraitDef::getCategory, "CUSTOM"));
        trait.setSortOrder((int) (customCount + 1) * 10);
        traitMapper.insert(trait);
        int order = 0;
        for (String o : options) {
            TraitOption option = new TraitOption();
            option.setTraitId(trait.getId());
            option.setValueCode(o);
            option.setLabel(o);
            option.setSortOrder(++order);
            optionMapper.insert(option);
        }
        List<String> aliases = req.aliases() == null ? List.of() : setAliases(trait.getId(), req.aliases());
        return new TraitView(trait, catalog.optionsOf(trait.getId()), aliases);
    }

    /** 只能删除还没有录过数据的自定义项目；标准项目只能停用 */
    public void deleteCustom(Long id) {
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

    /**
     * 设置一个项目的全部表头别名（替换原来的）。同一个写法只能对应一个项目。
     *
     * @return 规范化、去重后的别名
     */
    @Transactional
    public List<String> setAliases(Long traitId, List<String> aliases) {
        TraitDef trait = catalog.get(traitId);
        Set<String> wanted = new LinkedHashSet<>();
        for (String a : aliases) {
            String n = AliasText.normalize(a);
            if (n.isEmpty()) {
                continue;
            }
            if (n.length() > AliasText.MAX_LENGTH) {
                throw ApiException.badRequest("表头别名不能超过 " + AliasText.MAX_LENGTH + " 个字：" + a);
            }
            wanted.add(n);
        }
        if (!wanted.isEmpty()) {
            List<TraitAlias> taken = aliasMapper.selectList(Wrappers.<TraitAlias>lambdaQuery()
                    .in(TraitAlias::getAlias, wanted).ne(TraitAlias::getTraitId, traitId));
            if (!taken.isEmpty()) {
                TraitAlias first = taken.getFirst();
                throw ApiException.conflict("\"" + first.getAlias() + "\"已经是\""
                        + catalog.get(first.getTraitId()).getName() + "\"的别名");
            }
        }
        aliasMapper.delete(Wrappers.<TraitAlias>lambdaQuery().eq(TraitAlias::getTraitId, trait.getId()));
        LocalDateTime now = LocalDateTime.now(clock);
        for (String alias : wanted) {
            insertAlias(traitId, alias, now);
        }
        return List.copyOf(wanted);
    }

    /**
     * 记下导入时确认过的对应关系：表头 → 项目。这个写法原来对应别的项目时，改成对应这个项目（以最近一次为准）。
     *
     * @return 新记下或改过的别名个数
     */
    @Transactional
    public int rememberAliases(Map<String, Long> traitByHeader) {
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = 0;
        for (var e : traitByHeader.entrySet()) {
            String alias = AliasText.normalize(e.getKey());
            if (alias.isEmpty() || alias.length() > AliasText.MAX_LENGTH) {
                continue;
            }
            TraitAlias existing = aliasMapper.selectOne(Wrappers.<TraitAlias>lambdaQuery().eq(TraitAlias::getAlias, alias));
            if (existing == null) {
                insertAlias(e.getValue(), alias, now);
                changed++;
            } else if (!existing.getTraitId().equals(e.getValue())) {
                existing.setTraitId(e.getValue());
                existing.setCreatedAt(now);
                aliasMapper.updateById(existing);
                changed++;
            }
        }
        return changed;
    }

    private Map<Long, List<String>> aliasesByTrait() {
        return aliasMapper.selectList(Wrappers.<TraitAlias>lambdaQuery().orderByAsc(TraitAlias::getId)).stream()
                .collect(Collectors.groupingBy(TraitAlias::getTraitId,
                        Collectors.mapping(TraitAlias::getAlias, Collectors.toCollection(ArrayList::new))));
    }

    private void insertAlias(Long traitId, String alias, LocalDateTime now) {
        TraitAlias a = new TraitAlias();
        a.setTraitId(traitId);
        a.setAlias(alias);
        a.setCreatedAt(now);
        aliasMapper.insert(a);
    }
}
