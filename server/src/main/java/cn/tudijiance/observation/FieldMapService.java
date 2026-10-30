package cn.tudijiance.observation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.variety.Variety;

/**
 * 平面图上的小区状态、状态指标，以及右键查看的小区详情。
 *
 * <pre>
 * 小区状态：NONE 未种植 / GROWING 生长中 / ABNORMAL 有异常 / HARVESTED 已收获；保护区和走道为 GUARD / PATH
 * </pre>
 */
@Service
public class FieldMapService {

    private static final Set<String> HARVEST_TRAITS = Set.of("op_harvest", "plot_yield");

    private final ObservationMapper observationMapper;
    private final ObservationService observationService;
    private final TraitCatalog catalog;
    private final TrialContextLoader contextLoader;
    private final Clock clock;

    public FieldMapService(ObservationMapper observationMapper, ObservationService observationService,
                           TraitCatalog catalog, TrialContextLoader contextLoader, Clock clock) {
        this.observationMapper = observationMapper;
        this.observationService = observationService;
        this.catalog = catalog;
        this.contextLoader = contextLoader;
        this.clock = clock;
    }

    public TrialStatus status(Long trialId) {
        TrialContext ctx = contextLoader.load(trialId);
        List<Observation> observations = observationsOf(ctx);
        Map<Long, TraitDef> traits = catalog.all();
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        LocalDateTime weekStart = todayStart.minusDays(6);

        Set<Long> harvested = observations.stream()
                .filter(o -> HARVEST_TRAITS.contains(traits.get(o.getTraitId()).getCode()))
                .map(Observation::getPlantingId).collect(Collectors.toSet());
        Set<Long> enteredToday = observations.stream()
                .filter(o -> !o.getRecordedAt().isBefore(todayStart))
                .map(Observation::getPlantingId).collect(Collectors.toSet());

        List<PlotStatus> plots = new ArrayList<>();
        int total = 0;
        int planted = 0;
        int abnormal = 0;
        int harvestedCount = 0;
        for (Plot plot : ctx.plots().values()) {
            Planting planting = ctx.plantingsByPlot().get(plot.getId());
            String status;
            if (!Plot.KIND_PLOT.equals(plot.getKind())) {
                status = plot.getKind();
            } else {
                total++;
                if (planting == null || planting.getVarietyId() == null) {
                    status = "NONE";
                } else {
                    planted++;
                    if (Boolean.TRUE.equals(planting.getAbnormal())) {
                        status = "ABNORMAL";
                        abnormal++;
                    } else if (harvested.contains(planting.getId())) {
                        status = "HARVESTED";
                        harvestedCount++;
                    } else {
                        status = "GROWING";
                    }
                }
            }
            plots.add(new PlotStatus(plot.getId(), status,
                    planting != null && enteredToday.contains(planting.getId()),
                    planting == null ? null : planting.getAbnormalNote()));
        }
        long weekEntries = observations.stream().filter(o -> !o.getRecordedAt().isBefore(weekStart)).count();

        // 当前生育期：已经记录过的、排序最靠后的物候期项目
        String stage = observations.stream()
                .map(o -> traits.get(o.getTraitId()))
                .filter(t -> "PHENOLOGY".equals(t.getCategory()) && "DATE".equals(t.getDataType()))
                .max(Comparator.comparingInt(TraitDef::getSortOrder))
                .map(TraitDef::getName).orElse(null);

        Indicators indicators = new Indicators(total, planted, enteredToday.size(), abnormal, harvestedCount,
                weekEntries, stage);
        return new TrialStatus(plots, indicators);
    }

    public PlotDetail plotDetail(Long trialId, Long plotId) {
        TrialContext ctx = contextLoader.load(trialId);
        Plot plot = ctx.plots().get(plotId);
        if (plot == null) {
            throw ApiException.notFound("小区");
        }
        Planting planting = ctx.plantingsByPlot().get(plotId);
        Variety variety = ctx.varietyOf(planting);
        if (planting == null) {
            return new PlotDetail(plot, null, null, null, false, null, List.of(), List.of());
        }
        List<Observation> list = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                .eq(Observation::getPlantingId, planting.getId())
                .eq(Observation::getDeleted, false)
                .orderByDesc(Observation::getObservedOn).orderByDesc(Observation::getId));
        List<ObservationView> records = observationService.views(list, ctx);

        // 每个项目取最近一次的值，按标准项目、自定义项目分组显示
        Map<Long, TraitDef> traits = catalog.all();
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        Map<Long, Observation> latestByTrait = new LinkedHashMap<>();
        list.forEach(o -> latestByTrait.putIfAbsent(o.getTraitId(), o));
        List<LatestValue> latest = latestByTrait.values().stream()
                .map(o -> {
                    TraitDef t = traits.get(o.getTraitId());
                    return new LatestValue(t.getId(), t.getName(), t.getCategory(),
                            Boolean.TRUE.equals(t.getIsStandard()), t.getSortOrder(),
                            TraitValues.display(t, options.getOrDefault(t.getId(), List.of()),
                                    o.getValueNum(), o.getValueText(), o.getValueDate()),
                            o.getObservedOn());
                })
                .sorted(Comparator.comparing(LatestValue::category).thenComparingInt(LatestValue::sortOrder))
                .toList();
        return new PlotDetail(plot, planting.getId(), variety, planting.getReplicateNo(),
                Boolean.TRUE.equals(planting.getAbnormal()), planting.getAbnormalNote(),
                latest, records.size() > 200 ? records.subList(0, 200) : records);
    }

    private List<Observation> observationsOf(TrialContext ctx) {
        if (ctx.plantingsById().isEmpty()) {
            return List.of();
        }
        return observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                .in(Observation::getPlantingId, ctx.plantingsById().keySet())
                .eq(Observation::getDeleted, false));
    }

    public record PlotStatus(Long plotId, String status, boolean enteredToday, String abnormalNote) {
    }

    /** 状态指标：小区总数、已种植、今日已录入、有异常、已收获、近 7 天录入条数、当前生育期 */
    public record Indicators(int totalPlots, int planted, int enteredToday, int abnormal, int harvested,
                             long weekEntries, String currentStage) {
    }

    public record TrialStatus(List<PlotStatus> plots, Indicators indicators) {
    }

    public record LatestValue(Long traitId, String traitName, String category, boolean standard, int sortOrder,
                              String displayValue, LocalDate observedOn) {
    }

    public record PlotDetail(Plot plot, Long plantingId, Variety variety, Integer replicateNo,
                             boolean abnormal, String abnormalNote,
                             List<LatestValue> latest, List<ObservationView> records) {
    }
}
