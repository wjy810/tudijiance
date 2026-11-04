package cn.tudijiance.observation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.field.Plot;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;

/**
 * 每个小区（一个重复）某个项目的值：录入的项目取最近一次录的值，计算项目由录入的项目算出来。
 *
 * <pre>
 * 全生育期        成熟期 − 播种期（天）               A.2.1.6
 * 生育期          成熟期 − 出苗期（天）               A.2.1.7
 * 成穗率          有效穗数 ÷ 最高茎蘖数 × 100%        A.2.3.4
 * 标准面积小区产量 小区产量 × 13.33 ÷ 小区面积          A.3.8
 * 折合亩产        小区产量 × 666.7 ÷ 小区面积          A.3.8
 * </pre>
 *
 * "与对照比较"一类是品种之间的比较，在统计里算，不在这里。
 */
@Service
public class ComputedTraits {

    private static final BigDecimal MU = new BigDecimal("666.7");
    private static final BigDecimal STD_AREA = new BigDecimal("13.33");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 计算项目 → 用到的录入项目 */
    private static final Map<String, List<String>> SOURCES = Map.of(
            "whole_growth_days", List.of("sowing_date", "maturity_date"),
            "growth_days", List.of("emergence_date", "maturity_date"),
            "spike_rate", List.of("effective_spikes", "max_tillers"),
            "std_plot_yield", List.of("plot_yield"),
            "yield_per_mu", List.of("plot_yield"));

    private final ObservationMapper observationMapper;
    private final TraitCatalog catalog;

    public ComputedTraits(ObservationMapper observationMapper, TraitCatalog catalog) {
        this.observationMapper = observationMapper;
        this.catalog = catalog;
    }

    public static boolean isSupported(TraitDef trait) {
        return SOURCES.containsKey(trait.getCode());
    }

    public static Set<String> codes() {
        return SOURCES.keySet();
    }

    /** 能按数值统计、着色的项目：数值、分级，以及上面这些计算项目。 */
    public static boolean isNumeric(TraitDef trait) {
        if (Boolean.TRUE.equals(trait.getIsComputed())) {
            return isSupported(trait);
        }
        return "NUMBER".equals(trait.getDataType()) || "GRADE".equals(trait.getDataType());
    }

    /** 试验里每个种植记录（plantingId）这个项目的值，没有值的不在结果里。 */
    public Map<Long, BigDecimal> values(TrialContext ctx, TraitDef trait) {
        Map<Long, Map<String, Observation>> latest = latest(ctx.plantingsById().keySet(), List.of(trait));
        Map<Long, BigDecimal> result = new HashMap<>();
        latest.forEach((plantingId, byCode) -> {
            Plot plot = ctx.plots().get(ctx.plantingsById().get(plantingId).getPlotId());
            BigDecimal v = compute(trait, byCode, plot);
            if (v != null) {
                result.put(plantingId, v);
            }
        });
        return result;
    }

    /** 计算项目用到哪些录入项目；不是计算项目时就是它自己 */
    public static List<String> sourceCodes(TraitDef trait) {
        return isSupported(trait) ? SOURCES.get(trait.getCode()) : List.of(trait.getCode());
    }

    /**
     * 一批种植记录在这些项目上最近一次的记录（一次查询）：plantingId → (项目编码 → 记录)。
     * 计算项目换成它用到的录入项目，结果交给 {@link #compute} 算值。
     */
    public Map<Long, Map<String, Observation>> latest(Collection<Long> plantingIds, Collection<TraitDef> traits) {
        if (plantingIds.isEmpty() || traits.isEmpty()) {
            return Map.of();
        }
        Map<String, TraitDef> byCode = catalog.all().values().stream()
                .collect(Collectors.toMap(TraitDef::getCode, t -> t));
        Map<Long, TraitDef> byId = traits.stream().flatMap(t -> sourceCodes(t).stream()).distinct()
                .map(byCode::get).collect(Collectors.toMap(TraitDef::getId, t -> t));
        List<Observation> list = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                .in(Observation::getPlantingId, plantingIds)
                .in(Observation::getTraitId, byId.keySet())
                .eq(Observation::getDeleted, false)
                .orderByDesc(Observation::getObservedOn).orderByDesc(Observation::getId));
        Map<Long, Map<String, Observation>> latest = new HashMap<>();
        for (Observation o : list) {
            latest.computeIfAbsent(o.getPlantingId(), k -> new HashMap<>())
                    .putIfAbsent(byId.get(o.getTraitId()).getCode(), o);
        }
        return latest;
    }

    /**
     * 由一个小区各项目最近一次的记录算出 trait 的值。trait 不是计算项目时直接取它自己的数值。
     *
     * @param latestByCode 项目编码 → 这个小区最近一次的记录
     */
    public static BigDecimal compute(TraitDef trait, Map<String, Observation> latestByCode, Plot plot) {
        return switch (trait.getCode()) {
            case "whole_growth_days" -> days(latestByCode.get("sowing_date"), latestByCode.get("maturity_date"));
            case "growth_days" -> days(latestByCode.get("emergence_date"), latestByCode.get("maturity_date"));
            case "spike_rate" -> {
                BigDecimal spikes = num(latestByCode.get("effective_spikes"));
                BigDecimal tillers = num(latestByCode.get("max_tillers"));
                yield spikes == null || tillers == null || tillers.signum() == 0 ? null
                        : spikes.multiply(HUNDRED).divide(tillers, 1, RoundingMode.HALF_UP);
            }
            case "std_plot_yield" -> perArea(num(latestByCode.get("plot_yield")), plot, STD_AREA, 2);
            case "yield_per_mu" -> perArea(num(latestByCode.get("plot_yield")), plot, MU, 1);
            default -> num(latestByCode.get(trait.getCode()));
        };
    }

    /** 计算项目用到的记录里最晚的调查日期，显示"截至哪天的数据" */
    public static LocalDate latestDate(TraitDef trait, Map<String, Observation> latestByCode) {
        return SOURCES.getOrDefault(trait.getCode(), List.of()).stream()
                .map(latestByCode::get).filter(o -> o != null).map(Observation::getObservedOn)
                .max(LocalDate::compareTo).orElse(null);
    }

    public Collection<TraitDef> computedTraits() {
        return SOURCES.keySet().stream().map(catalog::byCode).toList();
    }

    private static BigDecimal days(Observation from, Observation to) {
        if (from == null || to == null || from.getValueDate() == null || to.getValueDate() == null) {
            return null;
        }
        long d = ChronoUnit.DAYS.between(from.getValueDate(), to.getValueDate());
        return d < 0 ? null : BigDecimal.valueOf(d);
    }

    private static BigDecimal num(Observation o) {
        return o == null ? null : o.getValueNum();
    }

    private static BigDecimal perArea(BigDecimal plotYield, Plot plot, BigDecimal factor, int scale) {
        BigDecimal area = plot == null ? null : plot.areaM2();
        if (plotYield == null || area == null) {
            return null;
        }
        return area.signum() == 0 ? null : plotYield.multiply(factor).divide(area, scale + 2, RoundingMode.HALF_UP);
    }
}
