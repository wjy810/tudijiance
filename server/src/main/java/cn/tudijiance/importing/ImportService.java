package cn.tudijiance.importing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.field.Plot;
import cn.tudijiance.importing.ImportDtos.CellError;
import cn.tudijiance.importing.ImportDtos.ColumnMapping;
import cn.tudijiance.importing.ImportDtos.ImportMapping;
import cn.tudijiance.importing.ImportDtos.ImportResult;
import cn.tudijiance.importing.ImportDtos.Preview;
import cn.tudijiance.importing.ImportDtos.PreviewSheet;
import cn.tudijiance.importing.SheetReader.SheetData;
import cn.tudijiance.observation.ObservationService;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.TrialDtos.AssignRequest;
import cn.tudijiance.trial.TrialDtos.Attribute;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

/**
 * 导入历史电子表格。一行是一个小区（一个重复）的记录，各列是录入项目。
 * 先找到这一行对应哪个小区（按小区编号，或按品种 + 重复），再把每一列的值按项目类型解析后保存。
 */
@Service
public class ImportService {

    private static final int PREVIEW_ROWS = 40;
    private static final int MAX_ERRORS = 300;
    private static final BigDecimal MU = new BigDecimal("666.7");

    /** 导入模板里的项目 */
    private static final List<String> TEMPLATE_TRAITS = List.of("sowing_date", "emergence_date", "heading_date",
            "maturity_date", "plant_height", "effective_spikes", "grains_per_spike", "thousand_grain_weight",
            "test_weight", "plot_yield");

    private final ObservationService observationService;
    private final TrialContextLoader contextLoader;
    private final TrialService trialService;
    private final TraitCatalog catalog;
    private final VarietyMapper varietyMapper;
    private final Clock clock;

    public ImportService(ObservationService observationService, TrialContextLoader contextLoader,
                         TrialService trialService, TraitCatalog catalog, VarietyMapper varietyMapper, Clock clock) {
        this.observationService = observationService;
        this.contextLoader = contextLoader;
        this.trialService = trialService;
        this.catalog = catalog;
        this.varietyMapper = varietyMapper;
        this.clock = clock;
    }

    public Preview preview(byte[] bytes, String filename) {
        return new Preview(SheetReader.read(bytes, filename).stream().map(s -> {
            int cols = s.rows().stream().mapToInt(List::size).max().orElse(0);
            List<List<String>> rows = s.rows().stream().limit(PREVIEW_ROWS)
                    .map(r -> r.stream().map(SheetReader::display).toList()).toList();
            return new PreviewSheet(s.name(), s.rows().size(), cols, rows);
        }).toList());
    }

    /** 一列要导入的项目。fromYieldPerMu 表示这一列是折合亩产，要换算成小区产量 */
    private record Target(int col, TraitDef trait, List<TraitOption> options, boolean fromYieldPerMu) {
    }

    /** 表格里的一行对应的小区 */
    private record RowTarget(int index, Long plotId) {
    }

    @Transactional
    public ImportResult run(byte[] bytes, String filename, ImportMapping m, long userId) {
        List<SheetData> sheets = SheetReader.read(bytes, filename);
        if (m.sheet() >= sheets.size()) {
            throw ApiException.badRequest("没有第 " + (m.sheet() + 1) + " 个工作表");
        }
        List<List<Object>> rows = sheets.get(m.sheet()).rows();
        if (m.headerRow() > rows.size()) {
            throw ApiException.badRequest("表头行超出了表格的行数");
        }
        if (m.plotCodeCol() == null && m.varietyCol() == null) {
            throw ApiException.badRequest("请指定小区编号列，或者品种列和重复列，用来找到每一行对应的小区");
        }
        int seasonYear = trialService.get(m.trialId()).getSeasonYear();
        List<Target> targets = targets(m.columns());
        if (targets.isEmpty()) {
            throw ApiException.badRequest("请至少选一列要导入的项目");
        }

        Errors errors = new Errors();
        TrialContext ctx = contextLoader.load(m.trialId());
        Map<String, Variety> varieties = varietyMapper.selectList(null).stream()
                .collect(Collectors.toMap(v -> v.getName().strip(), Function.identity(), (a, b) -> a));
        Set<String> newVarieties = new LinkedHashSet<>();
        Map<Long, String> wantVariety = new HashMap<>();
        Map<Long, Integer> wantReplicate = new HashMap<>();

        // 第一遍：每一行对应哪个小区
        List<RowTarget> rowTargets = new ArrayList<>();
        int dataRows = 0;
        for (int i = m.headerRow(); i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            if (row.stream().allMatch(Objects::isNull)) {
                continue;
            }
            dataRows++;
            Long plotId = m.plotCodeCol() != null
                    ? byPlotCode(ctx, row, i, m, varieties, newVarieties, wantVariety, wantReplicate, errors)
                    : byVarietyAndReplicate(ctx, row, i, m, errors);
            if (plotId != null) {
                rowTargets.add(new RowTarget(i, plotId));
            }
        }

        // 导入时先新增品种、设置布局，再导入数据
        int layoutChanges = 0;
        if (!m.dryRun()) {
            for (String name : newVarieties) {
                Variety v = new Variety();
                v.setName(name);
                v.setRemark("导入历史表格时新增");
                v.setCreatedAt(LocalDateTime.now(clock));
                varietyMapper.insert(v);
                varieties.put(name, v);
            }
            layoutChanges = applyLayout(ctx, wantVariety, wantReplicate, varieties);
            if (layoutChanges > 0) {
                ctx = contextLoader.load(m.trialId());
            }
        } else {
            layoutChanges = countLayoutChanges(ctx, wantVariety, wantReplicate, varieties);
        }

        // 第二遍：逐个格子解析、检查、导入
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        int created = 0;
        int duplicates = 0;
        for (RowTarget rt : rowTargets) {
            List<Object> row = rows.get(rt.index());
            LocalDate rowDate = m.observedOn();
            if (m.observedOnCol() != null && cell(row, m.observedOnCol()) != null) {
                rowDate = CellValues.date(cell(row, m.observedOnCol()), seasonYear);
                if (rowDate == null) {
                    errors.add(rt.index(), m.observedOnCol(), "看不懂的调查日期：" + CellValues.text(cell(row, m.observedOnCol())));
                    continue;
                }
            }
            Plot plot = ctx.plots().get(rt.plotId());
            for (Target t : targets) {
                Object raw = cell(row, t.col());
                if (raw == null || "-".equals(CellValues.text(raw)) || "—".equals(CellValues.text(raw))) {
                    continue;
                }
                try {
                    Parsed p = parse(t, raw, plot, seasonYear);
                    LocalDate observedOn = "DATE".equals(t.trait().getDataType()) ? p.date() : rowDate;
                    if (observedOn == null) {
                        throw ApiException.badRequest("没有调查日期：请在上一步填写调查日期，或指定调查日期列");
                    }
                    if (observedOn.isAfter(today)) {
                        throw ApiException.badRequest("日期 " + observedOn + " 晚于今天");
                    }
                    String uuid = UUID.nameUUIDFromBytes(("import|" + m.trialId() + "|" + rt.plotId() + "|"
                            + t.trait().getId() + "|" + observedOn + "|" + p.num() + "|" + p.text() + "|" + p.date())
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    if (m.dryRun()) {
                        observationService.checkValue(t.trait(), t.options(), p.num(), p.text(), p.date());
                        if (observationService.exists(uuid)) {
                            duplicates++;
                        } else {
                            created++;
                        }
                    } else if (observationService.importValue(ctx, rt.plotId(), t.trait(), t.options(), p.num(),
                            p.text(), p.date(), observedOn, uuid, userId, now)) {
                        created++;
                    } else {
                        duplicates++;
                    }
                } catch (ApiException e) {
                    errors.add(rt.index(), t.col(), e.getMessage());
                }
            }
        }
        return new ImportResult(m.dryRun(), dataRows, created, duplicates, errors.count, errors.sorted(),
                List.copyOf(newVarieties), layoutChanges);
    }

    private List<Target> targets(List<ColumnMapping> columns) {
        List<Target> targets = new ArrayList<>();
        for (ColumnMapping c : columns) {
            TraitDef t = catalog.get(c.traitId());
            if ("yield_per_mu".equals(t.getCode())) {
                TraitDef plotYield = catalog.byCode("plot_yield");
                targets.add(new Target(c.col(), plotYield, catalog.optionsOf(plotYield.getId()), true));
                continue;
            }
            if (Boolean.TRUE.equals(t.getIsComputed())) {
                throw ApiException.badRequest(t.getName() + "由系统自动计算，不用导入，请把这一列设为不导入");
            }
            // 农事操作只导入类型，用量不导
            targets.add(new Target(c.col(), t, catalog.optionsOf(t.getId()), false));
        }
        return targets;
    }

    private Long byPlotCode(TrialContext ctx, List<Object> row, int i, ImportMapping m, Map<String, Variety> varieties,
                            Set<String> newVarieties, Map<Long, String> wantVariety, Map<Long, Integer> wantReplicate,
                            Errors errors) {
        String code = CellValues.code(cell(row, m.plotCodeCol()));
        if (code == null) {
            errors.add(i, m.plotCodeCol(), "没有小区编号");
            return null;
        }
        Plot plot = ctx.plots().values().stream().filter(p -> code.equals(p.getCode())).findFirst().orElse(null);
        if (plot == null) {
            errors.add(i, m.plotCodeCol(), "实验田里没有编号为 " + code + " 的小区");
            return null;
        }
        if (!Plot.KIND_PLOT.equals(plot.getKind())) {
            errors.add(i, m.plotCodeCol(), "小区 " + code + " 是保护区或走道");
            return null;
        }
        if (m.assignLayout()) {
            if (m.varietyCol() != null) {
                String name = CellValues.text(cell(row, m.varietyCol()));
                if (name != null) {
                    if (!varieties.containsKey(name)) {
                        if (m.createVarieties()) {
                            newVarieties.add(name);
                        } else {
                            errors.add(i, m.varietyCol(), "品种表里没有\"" + name + "\"，可以勾选\"自动新增品种\"");
                            return null;
                        }
                    }
                    wantVariety.put(plot.getId(), name);
                }
            }
            if (m.replicateCol() != null && cell(row, m.replicateCol()) != null) {
                Integer rep = CellValues.replicate(cell(row, m.replicateCol()));
                if (rep == null || rep < 1 || rep > 20) {
                    errors.add(i, m.replicateCol(), "看不懂的重复号：" + CellValues.text(cell(row, m.replicateCol())));
                    return null;
                }
                wantReplicate.put(plot.getId(), rep);
            }
        }
        return plot.getId();
    }

    private Long byVarietyAndReplicate(TrialContext ctx, List<Object> row, int i, ImportMapping m, Errors errors) {
        String name = CellValues.text(cell(row, m.varietyCol()));
        if (name == null) {
            errors.add(i, m.varietyCol(), "没有品种名称");
            return null;
        }
        Integer rep = null;
        if (m.replicateCol() != null && cell(row, m.replicateCol()) != null) {
            rep = CellValues.replicate(cell(row, m.replicateCol()));
            if (rep == null) {
                errors.add(i, m.replicateCol(), "看不懂的重复号：" + CellValues.text(cell(row, m.replicateCol())));
                return null;
            }
        }
        Integer wanted = rep;
        List<Planting> matches = ctx.plantingsById().values().stream()
                .filter(p -> {
                    Variety v = ctx.varietyOf(p);
                    return v != null && v.getName().strip().equals(name)
                            && (wanted == null || wanted.equals(p.getReplicateNo()));
                })
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst().getPlotId();
        }
        String what = rep == null ? "品种\"" + name + "\"" : "品种\"" + name + "\"的重复 " + rep;
        errors.add(i, m.varietyCol(), matches.isEmpty()
                ? "这一年的平面图上没有" + what + "。请先在平面图上布置品种和重复，或者在表格里加上小区编号列"
                : what + "对应多个小区，请指定重复列或小区编号列");
        return null;
    }

    private record Parsed(BigDecimal num, String text, LocalDate date) {
    }

    private static Parsed parse(Target t, Object raw, Plot plot, int seasonYear) {
        TraitDef trait = t.trait();
        String shown = CellValues.text(raw);
        switch (trait.getDataType()) {
            case "DATE" -> {
                LocalDate d = CellValues.date(raw, seasonYear);
                if (d == null) {
                    throw ApiException.badRequest("看不懂的日期：" + shown);
                }
                return new Parsed(null, null, d);
            }
            case "GRADE" -> {
                TraitOption o = CellValues.option(raw, t.options());
                if (o == null) {
                    throw ApiException.badRequest("\"" + shown + "\"不是" + trait.getName() + "的级别");
                }
                return new Parsed(new BigDecimal(o.getValueCode()), null, null);
            }
            case "NUMBER" -> {
                BigDecimal n = CellValues.number(raw);
                if (n == null && !t.options().isEmpty()) {
                    TraitOption o = CellValues.option(raw, t.options());
                    n = o == null ? null : new BigDecimal(o.getValueCode());
                }
                if (n == null) {
                    throw ApiException.badRequest("\"" + shown + "\"不是数字");
                }
                if (t.fromYieldPerMu()) {
                    if (plot.getLengthM() == null || plot.getWidthM() == null) {
                        throw ApiException.badRequest("小区 " + plot.getCode() + " 没有填长和宽，不能由亩产换算小区产量");
                    }
                    n = n.multiply(plot.getLengthM().multiply(plot.getWidthM())).divide(MU, 2, RoundingMode.HALF_UP);
                }
                return new Parsed(n, null, null);
            }
            case "OPTION", "OPERATION" -> {
                if (t.options().isEmpty()) {
                    return new Parsed(null, shown, null);
                }
                TraitOption o = CellValues.option(raw, t.options());
                if (o == null) {
                    throw ApiException.badRequest("\"" + shown + "\"不在" + trait.getName() + "的选项里");
                }
                return new Parsed(null, o.getValueCode(), null);
            }
            default -> {
                return new Parsed(null, shown, null);
            }
        }
    }

    private int countLayoutChanges(TrialContext ctx, Map<Long, String> wantVariety, Map<Long, Integer> wantReplicate,
                                   Map<String, Variety> varieties) {
        int n = 0;
        for (var e : wantVariety.entrySet()) {
            Variety current = ctx.varietyOf(ctx.plantingsByPlot().get(e.getKey()));
            if (current == null || !current.getName().strip().equals(e.getValue())) {
                n++;
            }
        }
        for (var e : wantReplicate.entrySet()) {
            Planting p = ctx.plantingsByPlot().get(e.getKey());
            if (p == null || !e.getValue().equals(p.getReplicateNo())) {
                n++;
            }
        }
        return n;
    }

    /** 按表格设置这一年的品种和重复，同一个值的小区一起设 */
    private int applyLayout(TrialContext ctx, Map<Long, String> wantVariety, Map<Long, Integer> wantReplicate,
                            Map<String, Variety> varieties) {
        int changes = countLayoutChanges(ctx, wantVariety, wantReplicate, varieties);
        Map<Long, List<Long>> byVariety = new LinkedHashMap<>();
        wantVariety.forEach((plotId, name) ->
                byVariety.computeIfAbsent(varieties.get(name).getId(), k -> new ArrayList<>()).add(plotId));
        byVariety.forEach((varietyId, plotIds) ->
                trialService.assign(ctx.trial().getId(), new AssignRequest(plotIds, Attribute.VARIETY, varietyId)));
        Map<Integer, List<Long>> byReplicate = new LinkedHashMap<>();
        wantReplicate.forEach((plotId, rep) -> byReplicate.computeIfAbsent(rep, k -> new ArrayList<>()).add(plotId));
        byReplicate.forEach((rep, plotIds) -> trialService.assign(ctx.trial().getId(),
                new AssignRequest(plotIds, Attribute.REPLICATE, rep.longValue())));
        return changes;
    }

    /** 导入模板：这一年各试验小区的编号、品种、重复已经填好，后面是常用项目的空列 */
    public byte[] template(Long trialId) {
        TrialContext ctx = contextLoader.load(trialId);
        List<String> headers = new ArrayList<>(List.of("小区编号", "品种", "重复"));
        for (String code : TEMPLATE_TRAITS) {
            TraitDef t = catalog.byCode(code);
            headers.add(t.getUnit() == null ? t.getName() : t.getName() + "（" + t.getUnit() + "）");
        }
        List<List<Object>> rows = ctx.plots().values().stream()
                .filter(p -> Plot.KIND_PLOT.equals(p.getKind()))
                .sorted(Comparator.comparing(Plot::getRowNo).thenComparing(Plot::getColNo))
                .map(p -> {
                    Planting planting = ctx.plantingsByPlot().get(p.getId());
                    Variety v = ctx.varietyOf(planting);
                    List<Object> row = new ArrayList<>();
                    row.add(p.getCode() == null ? p.getRowNo() + "-" + p.getColNo() : p.getCode());
                    row.add(v == null ? null : v.getName());
                    row.add(planting == null ? null : planting.getReplicateNo());
                    return row;
                })
                .toList();
        return ExcelExport.write("导入模板", List.of(), headers, rows);
    }

    private static Object cell(List<Object> row, Integer col) {
        return col == null || col < 0 || col >= row.size() ? null : row.get(col);
    }

    /** 收集出错的格子，最多记 MAX_ERRORS 条，总数照常计；返回时按行、列排好 */
    private static final class Errors {
        private record Entry(int row, int col, CellError error) {
        }

        private final List<Entry> list = new ArrayList<>();
        int count;

        void add(int rowIndex, Integer col, String message) {
            count++;
            if (list.size() < MAX_ERRORS) {
                int c = col == null ? -1 : col;
                list.add(new Entry(rowIndex, c, new CellError(rowIndex + 1,
                        c < 0 ? "" : CellReference.convertNumToColString(c), message)));
            }
        }

        List<CellError> sorted() {
            return list.stream().sorted(Comparator.comparingInt(Entry::row).thenComparingInt(Entry::col))
                    .map(Entry::error).toList();
        }
    }
}
