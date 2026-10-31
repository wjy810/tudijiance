package cn.tudijiance.importing;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import cn.tudijiance.importing.ImportDtos.FieldMapping;
import cn.tudijiance.importing.ImportDtos.ImportMapping;
import cn.tudijiance.importing.ImportDtos.ImportResult;
import cn.tudijiance.importing.ImportDtos.Preview;
import cn.tudijiance.importing.ImportDtos.PreviewSheet;
import cn.tudijiance.importing.ImportDtos.Role;
import cn.tudijiance.importing.SheetReader.SheetData;
import cn.tudijiance.importing.ValueParser.Parsed;
import cn.tudijiance.importing.ValueParser.Target;
import cn.tudijiance.observation.ObservationService;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitService;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.TrialDtos.AssignRequest;
import cn.tudijiance.trial.TrialDtos.Attribute;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

/**
 * 导入历史电子表格。老师在页面上选好区域和方向（每列一个字段，或每行一个字段），给各字段指定用途；
 * 这里先找到每条记录对应的小区（按小区编号，或按品种 + 重复），再把各字段的值按录入项目的类型解析后保存。
 * 先检查（dryRun）再导入；同一个值重复导入不会产生重复数据。
 */
@Service
public class ImportService {

    private static final int MAX_ERRORS = 300;

    /** 导入模板里的项目 */
    private static final List<String> TEMPLATE_TRAITS = List.of("sowing_date", "emergence_date", "heading_date",
            "maturity_date", "plant_height", "effective_spikes", "grains_per_spike", "thousand_grain_weight",
            "test_weight", "plot_yield");

    private final ObservationService observationService;
    private final TrialContextLoader contextLoader;
    private final TrialService trialService;
    private final TraitCatalog catalog;
    private final TraitService traitService;
    private final VarietyMapper varietyMapper;
    private final Clock clock;

    public ImportService(ObservationService observationService, TrialContextLoader contextLoader,
                         TrialService trialService, TraitCatalog catalog, TraitService traitService,
                         VarietyMapper varietyMapper, Clock clock) {
        this.observationService = observationService;
        this.contextLoader = contextLoader;
        this.trialService = trialService;
        this.catalog = catalog;
        this.traitService = traitService;
        this.varietyMapper = varietyMapper;
        this.clock = clock;
    }

    /** 读出各工作表的全部内容（转成文字），页面上用来选区域、指定字段、改格子 */
    public Preview preview(byte[] bytes, String filename) {
        return new Preview(SheetReader.read(bytes, filename).stream().map(s -> {
            int cols = s.rows().stream().mapToInt(List::size).max().orElse(0);
            List<List<String>> rows = s.rows().stream()
                    .map(r -> r.stream().map(SheetReader::display).toList()).toList();
            return new PreviewSheet(s.name(), s.rows().size(), cols, rows);
        }).toList());
    }

    @Transactional
    public ImportResult run(byte[] bytes, String filename, ImportMapping m, long userId) {
        List<SheetData> sheets = SheetReader.read(bytes, filename);
        if (m.sheet() >= sheets.size()) {
            throw ApiException.badRequest("没有第 " + (m.sheet() + 1) + " 个工作表");
        }
        ImportTable table = ImportTable.of(sheets.get(m.sheet()).rows(), m.range(), m.orientation(), m.edits());
        Fields fields = resolveFields(m.fields(), table.fieldCount());
        int seasonYear = trialService.get(m.trialId()).getSeasonYear();
        TrialContext ctx = contextLoader.load(m.trialId());
        Errors errors = new Errors(table);

        Located located = locate(table, fields, ctx, m, errors);

        int layoutChanges;
        if (m.dryRun()) {
            layoutChanges = countLayoutChanges(ctx, located, varietiesByName());
        } else {
            Map<String, Variety> varieties = createVarieties(located.newVarieties());
            layoutChanges = applyLayout(ctx, located, varieties);
            if (layoutChanges > 0) {
                ctx = contextLoader.load(m.trialId());
            }
        }

        Counts counts = importValues(table, fields, located, ctx, m, seasonYear, userId, errors);
        int remembered = !m.dryRun() && m.rememberHeaders() ? traitService.rememberAliases(headerAliases(table, fields)) : 0;
        return new ImportResult(m.dryRun(), located.records(), counts.created(), counts.duplicates(), errors.count, errors.sorted(),
                List.copyOf(located.newVarieties()), layoutChanges, remembered);
    }

    // ---------- 字段的用途 ----------

    /** 各用途在第几个字段；TRAIT 可以有多个 */
    private record Fields(Map<Role, Integer> single, List<Target> targets) {
        Integer of(Role role) {
            return single.get(role);
        }
    }

    private Fields resolveFields(List<FieldMapping> mappings, int fieldCount) {
        Map<Role, Integer> single = new EnumMap<>(Role.class);
        List<Target> targets = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (FieldMapping f : mappings) {
            if (f.index() >= fieldCount) {
                throw ApiException.badRequest("第 " + (f.index() + 1) + " 个字段不在选中的范围里");
            }
            if (!used.add(f.index())) {
                throw ApiException.badRequest("第 " + (f.index() + 1) + " 个字段指定了两次");
            }
            if (f.role() != Role.TRAIT) {
                if (single.putIfAbsent(f.role(), f.index()) != null) {
                    throw ApiException.badRequest(ROLE_NAMES.get(f.role()) + "只能指定一个字段");
                }
                continue;
            }
            if (f.traitId() == null) {
                throw ApiException.badRequest("第 " + (f.index() + 1) + " 个字段没有选录入项目");
            }
            targets.add(target(f.index(), catalog.get(f.traitId())));
        }
        if (!single.containsKey(Role.PLOT_CODE) && !single.containsKey(Role.VARIETY)) {
            throw ApiException.badRequest("请指定小区编号字段，或者品种字段（加上重复字段），用来找到每条记录对应的小区");
        }
        if (targets.isEmpty()) {
            throw ApiException.badRequest("请至少指定一个要导入的录入项目");
        }
        return new Fields(single, targets);
    }

    private static final Map<Role, String> ROLE_NAMES = Map.of(Role.PLOT_CODE, "小区编号", Role.VARIETY, "品种",
            Role.REPLICATE, "重复", Role.OBSERVED_ON, "调查日期", Role.TRAIT, "录入项目");

    private Target target(int field, TraitDef trait) {
        if ("yield_per_mu".equals(trait.getCode())) {
            TraitDef plotYield = catalog.byCode("plot_yield");
            return new Target(field, trait, plotYield, catalog.optionsOf(plotYield.getId()), true);
        }
        if (Boolean.TRUE.equals(trait.getIsComputed())) {
            throw ApiException.badRequest(trait.getName() + "由系统自动计算，不用导入，请把这个字段设为不导入");
        }
        return new Target(field, trait, trait, catalog.optionsOf(trait.getId()), false);
    }

    // ---------- 找小区 ----------

    /**
     * @param plotByRecord  记录号 → 小区
     * @param wantVariety   按表格要设置的品种：小区 → 品种名称
     * @param wantReplicate 按表格要设置的重复：小区 → 重复号
     * @param records       有内容的记录数
     */
    private record Located(Map<Integer, Long> plotByRecord, Map<Long, String> wantVariety,
                           Map<Long, Integer> wantReplicate, Set<String> newVarieties, int records) {
    }

    private Located locate(ImportTable table, Fields fields, TrialContext ctx, ImportMapping m, Errors errors) {
        PlotLocator locator = new PlotLocator(ctx);
        Set<String> knownVarieties = varietiesByName().keySet();
        Map<Integer, Long> plotByRecord = new LinkedHashMap<>();
        Map<Long, String> wantVariety = new HashMap<>();
        Map<Long, Integer> wantReplicate = new HashMap<>();
        Set<String> newVarieties = new LinkedHashSet<>();
        int records = 0;
        for (int r = 0; r < table.recordCount(); r++) {
            if (table.isBlank(r)) {
                continue;
            }
            records++;
            Long plotId;
            if (fields.of(Role.PLOT_CODE) != null) {
                plotId = byPlotCode(table, r, fields, locator, errors);
                if (plotId != null && m.assignLayout()
                        && !layoutWish(table, r, fields, plotId, m, knownVarieties, wantVariety, wantReplicate, newVarieties, errors)) {
                    plotId = null;
                }
            } else {
                plotId = byVarietyAndReplicate(table, r, fields, locator, errors);
            }
            if (plotId != null) {
                plotByRecord.put(r, plotId);
            }
        }
        return new Located(plotByRecord, wantVariety, wantReplicate, newVarieties, records);
    }

    private static Long byPlotCode(ImportTable table, int r, Fields fields, PlotLocator locator, Errors errors) {
        int f = fields.of(Role.PLOT_CODE);
        String code = CellValues.code(table.value(r, f));
        if (code == null) {
            errors.add(r, f, "没有小区编号");
            return null;
        }
        Plot plot = locator.byCode(code);
        if (plot == null) {
            errors.add(r, f, "实验田里没有编号为 " + code + " 的小区");
            return null;
        }
        if (!Plot.KIND_PLOT.equals(plot.getKind())) {
            errors.add(r, f, "小区 " + code + " 是保护区或走道");
            return null;
        }
        return plot.getId();
    }

    /** 记下这条记录要设置的品种和重复；有看不懂的值时返回 false，这条记录不导入 */
    private static boolean layoutWish(ImportTable table, int r, Fields fields, Long plotId, ImportMapping m,
                                      Set<String> knownVarieties, Map<Long, String> wantVariety,
                                      Map<Long, Integer> wantReplicate, Set<String> newVarieties, Errors errors) {
        Integer vf = fields.of(Role.VARIETY);
        String name = vf == null ? null : CellValues.text(table.value(r, vf));
        if (name != null) {
            if (!knownVarieties.contains(name)) {
                if (!m.createVarieties()) {
                    errors.add(r, vf, "品种表里没有\"" + name + "\"，可以勾选\"自动新增品种\"");
                    return false;
                }
                newVarieties.add(name);
            }
            wantVariety.put(plotId, name);
        }
        Integer rf = fields.of(Role.REPLICATE);
        if (rf != null && table.value(r, rf) != null) {
            Integer rep = CellValues.replicate(table.value(r, rf));
            if (rep == null || rep < 1 || rep > 20) {
                errors.add(r, rf, "看不懂的重复号：" + CellValues.text(table.value(r, rf)));
                return false;
            }
            wantReplicate.put(plotId, rep);
        }
        return true;
    }

    private static Long byVarietyAndReplicate(ImportTable table, int r, Fields fields, PlotLocator locator, Errors errors) {
        int vf = fields.of(Role.VARIETY);
        String name = CellValues.text(table.value(r, vf));
        if (name == null) {
            errors.add(r, vf, "没有品种名称");
            return null;
        }
        Integer rep = null;
        Integer rf = fields.of(Role.REPLICATE);
        if (rf != null && table.value(r, rf) != null) {
            rep = CellValues.replicate(table.value(r, rf));
            if (rep == null) {
                errors.add(r, rf, "看不懂的重复号：" + CellValues.text(table.value(r, rf)));
                return null;
            }
        }
        List<Planting> matches = locator.byVariety(name, rep);
        if (matches.size() == 1) {
            return matches.getFirst().getPlotId();
        }
        String what = rep == null ? "品种\"" + name + "\"" : "品种\"" + name + "\"的重复 " + rep;
        errors.add(r, vf, matches.isEmpty()
                ? "这一年的平面图上没有" + what + "。请先在平面图上布置品种和重复，或者指定小区编号字段"
                : what + "对应多个小区，请指定重复字段或小区编号字段");
        return null;
    }

    // ---------- 按表格设置布局 ----------

    private Map<String, Variety> varietiesByName() {
        return varietyMapper.selectList(null).stream()
                .collect(Collectors.toMap(v -> v.getName().strip(), Function.identity(), (a, b) -> a, HashMap::new));
    }

    private Map<String, Variety> createVarieties(Set<String> names) {
        Map<String, Variety> varieties = varietiesByName();
        for (String name : names) {
            Variety v = new Variety();
            v.setName(name);
            v.setRemark("导入历史表格时新增");
            v.setCreatedAt(LocalDateTime.now(clock));
            varietyMapper.insert(v);
            varieties.put(name, v);
        }
        return varieties;
    }

    private static int countLayoutChanges(TrialContext ctx, Located located, Map<String, Variety> varieties) {
        int n = 0;
        for (var e : located.wantVariety().entrySet()) {
            Variety current = ctx.varietyOf(ctx.plantingsByPlot().get(e.getKey()));
            Variety wanted = varieties.get(e.getValue());
            if (current == null || wanted == null || !current.getId().equals(wanted.getId())) {
                n++;
            }
        }
        for (var e : located.wantReplicate().entrySet()) {
            Planting p = ctx.plantingsByPlot().get(e.getKey());
            if (p == null || !e.getValue().equals(p.getReplicateNo())) {
                n++;
            }
        }
        return n;
    }

    /** 按表格设置这一年的品种和重复，同一个值的小区一起设 */
    private int applyLayout(TrialContext ctx, Located located, Map<String, Variety> varieties) {
        int changes = countLayoutChanges(ctx, located, varieties);
        if (changes == 0) {
            return 0;
        }
        Long trialId = ctx.trial().getId();
        Map<Long, List<Long>> byVariety = new LinkedHashMap<>();
        located.wantVariety().forEach((plotId, name) ->
                byVariety.computeIfAbsent(varieties.get(name).getId(), k -> new ArrayList<>()).add(plotId));
        byVariety.forEach((varietyId, plotIds) ->
                trialService.assign(trialId, new AssignRequest(plotIds, Attribute.VARIETY, varietyId)));
        Map<Integer, List<Long>> byReplicate = new LinkedHashMap<>();
        located.wantReplicate().forEach((plotId, rep) -> byReplicate.computeIfAbsent(rep, k -> new ArrayList<>()).add(plotId));
        byReplicate.forEach((rep, plotIds) ->
                trialService.assign(trialId, new AssignRequest(plotIds, Attribute.REPLICATE, rep.longValue())));
        return changes;
    }

    // ---------- 导入各项目的值 ----------

    /**
     * @param created    导入（检查时是将导入）的值个数
     * @param duplicates 已经导入过而跳过的值个数
     */
    private record Counts(int created, int duplicates) {
    }

    private Counts importValues(ImportTable table, Fields fields, Located located, TrialContext ctx, ImportMapping m,
                               int seasonYear, long userId, Errors errors) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        Integer dateField = fields.of(Role.OBSERVED_ON);
        int created = 0;
        int duplicates = 0;
        for (var entry : located.plotByRecord().entrySet()) {
            int r = entry.getKey();
            Long plotId = entry.getValue();
            LocalDate recordDate = m.observedOn();
            if (dateField != null && table.value(r, dateField) != null) {
                recordDate = CellValues.date(table.value(r, dateField), seasonYear);
                if (recordDate == null) {
                    errors.add(r, dateField, "看不懂的调查日期：" + CellValues.text(table.value(r, dateField)));
                    continue;
                }
            }
            Plot plot = ctx.plots().get(plotId);
            for (Target t : fields.targets()) {
                Object raw = table.value(r, t.field());
                if (ValueParser.isEmpty(raw)) {
                    continue;
                }
                try {
                    Parsed p = ValueParser.parse(t, raw, plot, seasonYear);
                    LocalDate observedOn = "DATE".equals(t.stored().getDataType()) ? p.date() : recordDate;
                    if (observedOn == null) {
                        throw ApiException.badRequest("没有调查日期：请填写调查日期，或指定调查日期字段");
                    }
                    if (observedOn.isAfter(today)) {
                        throw ApiException.badRequest("日期 " + observedOn + " 晚于今天");
                    }
                    String uuid = UUID.nameUUIDFromBytes(("import|" + m.trialId() + "|" + plotId + "|"
                            + t.stored().getId() + "|" + observedOn + "|" + p.num() + "|" + p.text() + "|" + p.date())
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    boolean isNew;
                    if (m.dryRun()) {
                        observationService.checkValue(t.stored(), t.options(), p.num(), p.text(), p.date());
                        isNew = !observationService.exists(uuid);
                    } else {
                        isNew = observationService.importValue(ctx, plotId, t.stored(), t.options(), p.num(),
                                p.text(), p.date(), observedOn, uuid, userId, now);
                    }
                    if (isNew) {
                        created++;
                    } else {
                        duplicates++;
                    }
                } catch (ApiException e) {
                    errors.add(r, t.field(), e.getMessage());
                }
            }
        }
        return new Counts(created, duplicates);
    }

    /** 录入项目字段的"表头 → 项目"，用来记成别名 */
    private static Map<String, Long> headerAliases(ImportTable table, Fields fields) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Target t : fields.targets()) {
            String header = table.header(t.field());
            if (!header.isBlank()) {
                map.put(header, t.mapped().getId());
            }
        }
        return map;
    }

    // ---------- 导入模板 ----------

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

    /** 收集出错的格子，最多记 MAX_ERRORS 条，总数照常计；返回时按工作表里的行、列排好 */
    private static final class Errors {

        private final ImportTable table;
        private final List<CellError> list = new ArrayList<>();
        int count;

        Errors(ImportTable table) {
            this.table = table;
        }

        void add(int record, int field, String message) {
            count++;
            if (list.size() < MAX_ERRORS) {
                int r = table.sheetRow(record, field);
                int c = table.sheetCol(record, field);
                list.add(new CellError(r + 1, CellReference.convertNumToColString(c), r, c, message));
            }
        }

        List<CellError> sorted() {
            return list.stream().sorted(Comparator.comparingInt(CellError::r).thenComparingInt(CellError::c)).toList();
        }
    }
}
