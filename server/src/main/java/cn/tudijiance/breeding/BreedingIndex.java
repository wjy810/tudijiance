package cn.tudijiance.breeding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldMapper;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialMapper;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

/**
 * 育种的全部季、地块、种植记录和品系，一次取出来（每年几百个品系，全取也不多），
 * 用来算候选品系、世代、系谱图。
 */
public record BreedingIndex(
        Map<Long, Season> seasons,
        List<Season> ordered,
        Map<Long, List<Long>> follows,
        Map<Long, Field> fields,
        Map<Long, Trial> trialByField,
        Map<Long, Long> seasonOfTrial,
        List<Planting> plantings,
        Map<Long, Variety> lines) {

    /** 按开始日期排序 */
    static final Comparator<Season> BY_START = Comparator.comparing(Season::getStartDate).thenComparing(Season::getId);

    public Long seasonOf(Planting p) {
        return seasonOfTrial.get(p.getTrialId());
    }

    public List<Planting> plantingsOf(Long lineId) {
        return plantings.stream().filter(p -> lineId.equals(p.getVarietyId())).toList();
    }

    /** 某一季（所有地块）种了哪些品系 */
    public Set<Long> linesIn(Long seasonId) {
        return plantings.stream().filter(p -> seasonId.equals(seasonOf(p)))
                .map(Planting::getVarietyId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 品系种过的季：季 id → 世代（按季的先后） */
    public Map<Long, Integer> plantedSeasons(Long lineId) {
        Map<Long, Integer> result = new java.util.LinkedHashMap<>();
        plantingsOf(lineId).stream()
                .sorted(Comparator.comparing(p -> seasons.get(seasonOf(p)), BY_START))
                .forEach(p -> {
                    Long s = seasonOf(p);
                    if (!result.containsKey(s) || (result.get(s) == null && p.getGeneration() != null)) {
                        result.put(s, p.getGeneration());
                    }
                });
        return result;
    }

    /** 子代：父母（或选株的来源）是 lineId 的品系 */
    public List<Variety> childrenOf(Long lineId) {
        return lines.values().stream()
                .filter(v -> lineId.equals(v.getMotherId()) || lineId.equals(v.getFatherId())
                        || lineId.equals(v.getParentId()))
                .sorted(Comparator.comparing(Variety::getId))
                .toList();
    }

    public List<Variety> parentsOf(Variety v) {
        List<Variety> list = new ArrayList<>();
        for (Long id : new Long[] {v.getMotherId(), v.getFatherId(), v.getParentId()}) {
            if (id != null && lines.containsKey(id)) {
                list.add(lines.get(id));
            }
        }
        return list;
    }

    /** 在 season 之前开始的季，最近的在前 */
    public List<Season> before(Season season) {
        List<Season> list = ordered.stream().filter(s -> BY_START.compare(s, season) < 0).collect(Collectors.toList());
        java.util.Collections.reverse(list);
        return list;
    }

    @Component
    public static class Loader {

        private final SeasonMapper seasonMapper;
        private final SeasonFollowMapper followMapper;
        private final FieldMapper fieldMapper;
        private final TrialMapper trialMapper;
        private final PlantingMapper plantingMapper;
        private final VarietyMapper varietyMapper;

        public Loader(SeasonMapper seasonMapper, SeasonFollowMapper followMapper, FieldMapper fieldMapper,
                      TrialMapper trialMapper, PlantingMapper plantingMapper, VarietyMapper varietyMapper) {
            this.seasonMapper = seasonMapper;
            this.followMapper = followMapper;
            this.fieldMapper = fieldMapper;
            this.trialMapper = trialMapper;
            this.plantingMapper = plantingMapper;
            this.varietyMapper = varietyMapper;
        }

        public BreedingIndex load() {
            List<Season> seasonList = seasonMapper.selectList(null);
            Map<Long, Season> seasons = seasonList.stream().collect(Collectors.toMap(Season::getId, s -> s));
            List<Season> ordered = seasonList.stream().sorted(BY_START).toList();
            Map<Long, List<Long>> follows = new HashMap<>();
            followMapper.selectList(null).forEach(f ->
                    follows.computeIfAbsent(f.getSeasonId(), k -> new ArrayList<>()).add(f.getPrevSeasonId()));

            Map<Long, Field> fields = fieldMapper.selectList(Wrappers.<Field>lambdaQuery().isNotNull(Field::getSeasonId))
                    .stream().collect(Collectors.toMap(Field::getId, f -> f));
            Map<Long, Trial> trialByField = new HashMap<>();
            Map<Long, Long> seasonOfTrial = new HashMap<>();
            if (!fields.isEmpty()) {
                trialMapper.selectList(Wrappers.<Trial>lambdaQuery().in(Trial::getFieldId, fields.keySet())
                        .orderByAsc(Trial::getId)).forEach(t -> {
                            trialByField.putIfAbsent(t.getFieldId(), t);
                            seasonOfTrial.put(t.getId(), fields.get(t.getFieldId()).getSeasonId());
                        });
            }
            List<Planting> plantings = seasonOfTrial.isEmpty() ? List.of()
                    : plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                            .in(Planting::getTrialId, seasonOfTrial.keySet())
                            .isNotNull(Planting::getVarietyId));
            Map<Long, Variety> lines = varietyMapper.selectList(Wrappers.<Variety>lambdaQuery()
                            .isNotNull(Variety::getLineKind))
                    .stream().collect(Collectors.toMap(Variety::getId, v -> v));
            return new BreedingIndex(seasons, ordered, follows, fields, trialByField, seasonOfTrial, plantings, lines);
        }
    }
}
