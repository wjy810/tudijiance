package cn.tudijiance.stats;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.stats.StatsService.VarietyRow;
import cn.tudijiance.stats.StatsService.VarietySummary;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trial.Planting;

/**
 * 品种主要性状汇总表：每个品种一行，列是生育期、株高、产量构成、折合亩产等，
 * 再加上全生育期比对照、亩产比对照增减和位次。对应标准第 8 章年度总结里的汇总表。
 * 没有任何数据的列不显示。
 */
@Service
public class TrialReportService {

    /** 汇总表的列，按标准附录 A 的顺序 */
    static final List<String> COLUMNS = List.of("whole_growth_days", "plant_height", "basic_seedlings", "max_tillers",
            "effective_spikes", "spike_rate", "grains_per_spike", "thousand_grain_weight", "test_weight",
            "yield_per_mu");

    private final StatsService statsService;
    private final TrialContextLoader contextLoader;
    private final TraitCatalog catalog;
    private final FieldService fieldService;
    private final Clock clock;

    public TrialReportService(StatsService statsService, TrialContextLoader contextLoader, TraitCatalog catalog,
                              FieldService fieldService, Clock clock) {
        this.statsService = statsService;
        this.contextLoader = contextLoader;
        this.catalog = catalog;
        this.fieldService = fieldService;
        this.clock = clock;
    }

    public TrialReport report(Long trialId) {
        TrialContext ctx = contextLoader.load(trialId);
        Map<Long, Long> plotsByVariety = ctx.plantingsById().values().stream()
                .filter(p -> p.getVarietyId() != null)
                .collect(Collectors.groupingBy(Planting::getVarietyId, Collectors.counting()));

        List<ReportColumn> columns = new ArrayList<>();
        Map<String, Map<Long, VarietyRow>> byCode = new HashMap<>();
        String controlName = null;
        for (String code : COLUMNS) {
            TraitDef t = catalog.byCode(code);
            VarietySummary s = statsService.varietySummary(trialId, t.getId());
            if (s.rows().isEmpty()) {
                continue;
            }
            columns.add(new ReportColumn(t.getId(), code, t.getName(), t.getUnit()));
            byCode.put(code, s.rows().stream().collect(Collectors.toMap(VarietyRow::varietyId, Function.identity())));
            if (controlName == null) {
                controlName = s.controlVariety();
            }
        }

        Map<Long, VarietyRow> yield = byCode.getOrDefault("yield_per_mu", Map.of());
        Map<Long, VarietyRow> days = byCode.getOrDefault("whole_growth_days", Map.of());
        BigDecimal ckDays = days.values().stream().filter(VarietyRow::control).map(VarietyRow::value)
                .findFirst().orElse(null);

        List<ReportRow> rows = plotsByVariety.keySet().stream()
                .map(ctx.varieties()::get)
                .map(v -> {
                    List<BigDecimal> values = columns.stream()
                            .map(c -> {
                                VarietyRow r = byCode.get(c.code()).get(v.getId());
                                return r == null ? null : r.value();
                            })
                            .toList();
                    VarietyRow y = yield.get(v.getId());
                    VarietyRow d = days.get(v.getId());
                    BigDecimal daysVsCk = ckDays == null || d == null || v.getControlType() != null ? null
                            : d.value().subtract(ckDays);
                    return new ReportRow(v.getId(), v.getName(), v.getControlType() != null,
                            plotsByVariety.get(v.getId()).intValue(), values, daysVsCk,
                            y == null ? null : y.vsCkPercent(), y == null ? null : y.rank());
                })
                // 有亩产时按位次排，没有时对照在前、其余按名称
                .sorted(Comparator.comparing((ReportRow r) -> r.yieldRank() == null ? Integer.MAX_VALUE : r.yieldRank())
                        .thenComparing(r -> !r.control())
                        .thenComparing(ReportRow::varietyName))
                .toList();
        return new TrialReport(ctx.trial().getId(), ctx.trial().getName(), ctx.trial().getSeasonYear(),
                fieldService.get(ctx.trial().getFieldId()).getName(), controlName, columns, rows,
                !days.isEmpty(), !yield.isEmpty());
    }

    public byte[] excel(Long trialId) {
        TrialReport r = report(trialId);
        List<String> headers = new ArrayList<>(List.of("位次", "品种", "小区数"));
        for (ReportColumn c : r.columns()) {
            headers.add(c.unit() == null || "级".equals(c.unit()) ? c.name() : c.name() + "（" + c.unit() + "）");
            if ("whole_growth_days".equals(c.code())) {
                headers.add("全生育期比对照（天）");
            }
        }
        if (r.hasYield()) {
            headers.add("亩产比对照（%）");
        }
        List<List<Object>> rows = r.rows().stream().map(row -> {
            List<Object> cells = new ArrayList<>();
            cells.add(row.yieldRank());
            cells.add(row.control() ? row.varietyName() + "（CK）" : row.varietyName());
            cells.add(row.plots());
            for (int i = 0; i < r.columns().size(); i++) {
                cells.add(row.values().get(i));
                if ("whole_growth_days".equals(r.columns().get(i).code())) {
                    cells.add(row.growthDaysVsCk());
                }
            }
            if (r.hasYield()) {
                cells.add(row.yieldVsCkPercent());
            }
            return cells;
        }).toList();
        String subtitle = r.fieldName() + " · " + r.seasonYear() + " 年 · 对照品种："
                + (r.controlVariety() == null ? "未设" : r.controlVariety())
                + " · 导出时间 " + LocalDateTime.now(clock).withSecond(0).withNano(0).toString().replace('T', ' ');
        return ExcelExport.write("品种主要性状汇总表", List.of(r.trialName() + " 品种主要性状汇总表", subtitle,
                "各品种的值为各重复的汇总（多数项目取平均值）；比对照增减按丰产对照计算"), headers, rows);
    }

    public record ReportColumn(Long traitId, String code, String name, String unit) {
    }

    /** values 和 columns 一一对应，没有数据的是 null */
    public record ReportRow(Long varietyId, String varietyName, boolean control, int plots, List<BigDecimal> values,
                            BigDecimal growthDaysVsCk, BigDecimal yieldVsCkPercent, Integer yieldRank) {
    }

    public record TrialReport(Long trialId, String trialName, Integer seasonYear, String fieldName,
                              String controlVariety, List<ReportColumn> columns, List<ReportRow> rows,
                              boolean hasGrowthDays, boolean hasYield) {
    }
}
