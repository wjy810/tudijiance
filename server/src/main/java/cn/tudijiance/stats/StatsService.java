package cn.tudijiance.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldLayout;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ComputedTraits;
import cn.tudijiance.observation.FieldMapService;
import cn.tudijiance.observation.FieldMapService.TrialStatus;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.observation.ObservationService;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;

/**
 * 统计：按品种汇总、多年变化、大屏。
 *
 * 每个小区（一个重复）取这个项目最近一次的值，再按项目的汇总方法（附录 D）把同一品种的各个重复汇总成一个值。
 * 折合亩产按标准 A.3.8 由小区产量和小区面积计算：亩产 = 小区产量 × 666.7 ÷ 小区面积。
 */
@Service
public class StatsService {

    private static final int RECENT_LIMIT = 8;
    private static final int RECENT_SCAN = 300;

    private final ObservationMapper observationMapper;
    private final ObservationService observationService;
    private final FieldMapService fieldMapService;
    private final TrialContextLoader contextLoader;
    private final TrialService trialService;
    private final FieldService fieldService;
    private final TraitCatalog catalog;
    private final ComputedTraits computedTraits;
    private final Clock clock;

    public StatsService(ObservationMapper observationMapper, ObservationService observationService,
                        FieldMapService fieldMapService, TrialContextLoader contextLoader, TrialService trialService,
                        FieldService fieldService, TraitCatalog catalog, ComputedTraits computedTraits, Clock clock) {
        this.observationMapper = observationMapper;
        this.observationService = observationService;
        this.fieldMapService = fieldMapService;
        this.contextLoader = contextLoader;
        this.trialService = trialService;
        this.fieldService = fieldService;
        this.catalog = catalog;
        this.computedTraits = computedTraits;
        this.clock = clock;
    }

    /** 一个试验里，某个数值或分级项目按品种汇总，并算出比对照增减和位次。 */
    public VarietySummary varietySummary(Long trialId, Long traitId) {
        TraitDef trait = numericTrait(traitId);
        TrialContext ctx = contextLoader.load(trialId);
        Map<Long, BigDecimal> perPlanting = valuesPerPlanting(ctx, trait);

        Map<Long, List<BigDecimal>> byVariety = new LinkedHashMap<>();
        for (Planting p : ctx.plantingsById().values()) {
            BigDecimal v = perPlanting.get(p.getId());
            if (p.getVarietyId() != null && v != null) {
                byVariety.computeIfAbsent(p.getVarietyId(), k -> new ArrayList<>()).add(v);
            }
        }
        int scale = trait.getPrecisionDigits() == null ? 2 : trait.getPrecisionDigits();
        Map<Long, BigDecimal> aggregated = new HashMap<>();
        byVariety.forEach((varietyId, values) -> aggregated.put(varietyId, aggregate(trait.getAggMethod(), values, scale)));

        Variety ck = controlVariety(ctx, aggregated.keySet());
        BigDecimal ckValue = ck == null ? null : aggregated.get(ck.getId());
        List<Map.Entry<Long, BigDecimal>> ranked = aggregated.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed()).toList();
        List<VarietyRow> rows = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Variety v = ctx.varieties().get(ranked.get(i).getKey());
            BigDecimal value = ranked.get(i).getValue();
            BigDecimal vsCk = ckValue == null || ckValue.signum() == 0 || v.getId().equals(ck.getId()) ? null
                    : value.subtract(ckValue).multiply(BigDecimal.valueOf(100)).divide(ckValue, 1, RoundingMode.HALF_UP);
            rows.add(new VarietyRow(v.getId(), v.getName(), v.getControlType() != null,
                    byVariety.get(v.getId()).size(), value, vsCk, i + 1));
        }
        return new VarietySummary(trait.getId(), trait.getName(), trait.getUnit(),
                ctx.trial().getSeasonYear(), ck == null ? null : ck.getName(), rows);
    }

    /** 一块实验田历年的变化：每年一个试验，按品种汇总。还没有这个项目数据的年份（例如刚播种的当年）不列出。 */
    public MultiYear multiYear(Long fieldId, Long traitId) {
        TraitDef trait = numericTrait(traitId);
        List<Trial> trials = trialService.list(fieldId).stream()
                .sorted(Comparator.comparing(Trial::getSeasonYear).thenComparing(Trial::getId)).toList();
        List<Integer> years = new ArrayList<>();
        Map<Long, Series> series = new LinkedHashMap<>();
        for (Trial trial : trials) {
            VarietySummary summary = varietySummary(trial.getId(), traitId);
            if (summary.rows().isEmpty()) {
                continue;
            }
            int i = years.size();
            years.add(trial.getSeasonYear());
            for (VarietyRow row : summary.rows()) {
                Series s = series.computeIfAbsent(row.varietyId(),
                        k -> new Series(row.varietyId(), row.varietyName(), row.control(), new ArrayList<>()));
                while (s.values().size() < i) {
                    s.values().add(null);
                }
                s.values().add(row.value());
            }
        }
        series.values().forEach(s -> {
            while (s.values().size() < years.size()) {
                s.values().add(null);
            }
        });
        return new MultiYear(trait.getId(), trait.getName(), trait.getUnit(), years, List.copyOf(series.values()));
    }

    /**
     * 大屏：一块实验田最新一年的状态、亩产对比、历年趋势和最新录入。
     * 亩产对比用最近一个有产量数据的试验（当年还没收获时显示上一年的），yieldYear 是它的年份。
     */
    public Screen screen(Long fieldId) {
        List<Field> fields = fieldService.list();
        if (fields.isEmpty()) {
            return new Screen(null, null, null, 0, 0, null, List.of(), List.of(), null, List.of(), List.of(),
                    LocalDateTime.now(clock), null);
        }
        Field field = fieldId == null ? fields.getFirst()
                : fields.stream().filter(f -> f.getId().equals(fieldId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("实验田"));
        List<Trial> trials = trialService.list(field.getId()).stream()
                .sorted(Comparator.comparing(Trial::getSeasonYear).thenComparing(Trial::getId).reversed()).toList();
        Trial trial = trials.isEmpty() ? null : trials.getFirst();
        List<ScreenPlot> plots = new ArrayList<>();
        if (trial == null) {
            fieldService.plots(field.getId()).forEach(p -> plots.add(
                    new ScreenPlot(p.getId(), p.getRowNo(), p.getColNo(), p.getCode(), null,
                            Plot.KIND_PLOT.equals(p.getKind()) ? "NONE" : p.getKind(), null)));
            return new Screen(field.getName(), null, null, field.getRowCount(), field.getColCount(), null,
                    plots, List.of(), null, List.of(), List.of(), LocalDateTime.now(clock), fieldService.savedLayout(field));
        }
        TrialContext ctx = contextLoader.load(trial.getId());
        TrialStatus status = fieldMapService.status(trial.getId());
        Map<Long, FieldMapService.PlotStatus> statusByPlot = status.plots().stream()
                .collect(Collectors.toMap(FieldMapService.PlotStatus::plotId, ps -> ps));
        ctx.plots().values().stream()
                .sorted(Comparator.comparing(Plot::getRowNo).thenComparing(Plot::getColNo))
                .forEach(p -> {
                    Variety v = ctx.varietyOf(ctx.plantingsByPlot().get(p.getId()));
                    FieldMapService.PlotStatus ps = statusByPlot.get(p.getId());
                    plots.add(new ScreenPlot(p.getId(), p.getRowNo(), p.getColNo(), p.getCode(),
                            v == null ? null : v.getName(), ps == null ? null : ps.status(),
                            ps == null ? null : ps.abnormalNote()));
                });

        TraitDef yield = catalog.byCode("yield_per_mu");
        List<VarietyRow> yieldRows = List.of();
        Integer yieldYear = null;
        for (Trial t : trials) {
            List<VarietyRow> rows = varietySummary(t.getId(), yield.getId()).rows();
            if (!rows.isEmpty()) {
                yieldRows = rows;
                yieldYear = t.getSeasonYear();
                break;
            }
        }
        List<TrendPoint> trend = new ArrayList<>();
        MultiYear multi = multiYear(field.getId(), yield.getId());
        for (int i = 0; i < multi.years().size(); i++) {
            int idx = i;
            List<BigDecimal> values = multi.series().stream().map(s -> s.values().get(idx))
                    .filter(Objects::nonNull).toList();
            if (!values.isEmpty()) {
                trend.add(new TrendPoint(multi.years().get(i), aggregate("AVG", values, 1)));
            }
        }
        List<Observation> recentRows = ctx.plantingsById().isEmpty() ? List.of()
                : observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                .in(Observation::getPlantingId, ctx.plantingsById().keySet())
                .eq(Observation::getDeleted, false)
                .orderByDesc(Observation::getRecordedAt).orderByDesc(Observation::getId)
                .last("LIMIT " + RECENT_SCAN));
        List<RecentEntry> recent = groupRecent(observationService.views(recentRows, ctx));
        return new Screen(field.getName(), trial.getName(), trial.getSeasonYear(), field.getRowCount(),
                field.getColCount(), status.indicators(), plots, yieldRows, yieldYear, trend, recent,
                LocalDateTime.now(clock), fieldService.savedLayout(field));
    }

    /**
     * 最新录入：一次批量录入会给很多小区各存一条，这里把同一个人、同一分钟、同一项目、同一个值的合成一条，
     * 显示成"给 8 个小区录入灌溉"。最多 RECENT_LIMIT 条。
     */
    private static List<RecentEntry> groupRecent(List<ObservationView> views) {
        Map<String, RecentEntry> groups = new LinkedHashMap<>();
        for (ObservationView o : views) {
            String key = o.recordedBy() + "|" + o.recordedAt().truncatedTo(ChronoUnit.MINUTES) + "|"
                    + o.traitId() + "|" + o.displayValue();
            RecentEntry g = groups.get(key);
            if (g == null) {
                if (groups.size() == RECENT_LIMIT) {
                    break;
                }
                groups.put(key, new RecentEntry(o.recordedAt(), o.recordedBy(), o.plotCode(), 1, o.traitName(),
                        o.displayValue()));
            } else {
                groups.put(key, new RecentEntry(g.at(), g.user(), g.plotCode(), g.plotCount() + 1, g.traitName(),
                        g.value()));
            }
        }
        return List.copyOf(groups.values());
    }

    private Map<Long, BigDecimal> valuesPerPlanting(TrialContext ctx, TraitDef trait) {
        return computedTraits.values(ctx, trait);
    }

    private TraitDef numericTrait(Long traitId) {
        TraitDef trait = catalog.get(traitId);
        if (!ComputedTraits.isNumeric(trait)) {
            throw ApiException.badRequest(trait.getName() + "不是数值或分级项目，不能做统计图");
        }
        return trait;
    }

    /** 同一品种有多个对照时优先用丰产对照。 */
    private static Variety controlVariety(TrialContext ctx, Set<Long> varietyIds) {
        return varietyIds.stream().map(ctx.varieties()::get)
                .filter(v -> v.getControlType() != null)
                .min(Comparator.comparing(v -> "YIELD".equals(v.getControlType()) ? 0 : 1))
                .orElse(null);
    }

    static BigDecimal aggregate(String method, List<BigDecimal> values, int scale) {
        return switch (method) {
            case "MAX" -> values.stream().max(BigDecimal::compareTo).orElseThrow().setScale(scale, RoundingMode.HALF_UP);
            case "MIN" -> values.stream().min(BigDecimal::compareTo).orElseThrow().setScale(scale, RoundingMode.HALF_UP);
            case "MODE" -> values.stream()
                    .collect(Collectors.groupingBy(v -> v.stripTrailingZeros(), Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.<BigDecimal, Long>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).orElseThrow().setScale(scale, RoundingMode.HALF_UP);
            default -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
        };
    }

    public record VarietyRow(Long varietyId, String varietyName, boolean control, int plots,
                             BigDecimal value, BigDecimal vsCkPercent, int rank) {
    }

    public record VarietySummary(Long traitId, String traitName, String unit, Integer seasonYear,
                                 String controlVariety, List<VarietyRow> rows) {
    }

    public record Series(Long varietyId, String varietyName, boolean control, List<BigDecimal> values) {
    }

    public record MultiYear(Long traitId, String traitName, String unit, List<Integer> years, List<Series> series) {
    }

    public record ScreenPlot(Long plotId, int rowNo, int colNo, String code, String varietyName, String status,
                             String abnormalNote) {
    }

    public record TrendPoint(int year, BigDecimal value) {
    }

    /** plotCode 是其中一个小区的编号，plotCount 是这一次录了几个小区。 */
    public record RecentEntry(LocalDateTime at, String user, String plotCode, int plotCount, String traitName,
                              String value) {
    }

    /** layout：保存过区块布局时按区块画，否则为 null，按 rowNo、colNo 画 */
    public record Screen(String fieldName, String trialName, Integer seasonYear, int rowCount, int colCount,
                         FieldMapService.Indicators indicators, List<ScreenPlot> plots, List<VarietyRow> yieldRows,
                         Integer yieldYear, List<TrendPoint> trend, List<RecentEntry> recent,
                         LocalDateTime generatedAt, FieldLayout layout) {
    }
}
