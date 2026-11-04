package cn.tudijiance.stats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ComputedTraits;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.TrialBookService;
import cn.tudijiance.trial.TrialDtos.TrialBookView;
import cn.tudijiance.variety.Variety;

/**
 * 按《河南省小麦品种试验记载本》的样子导出一个试验：封面、田间种植图、基本情况、气象资料、
 * 田间记载（按重复分段）、室内考种、产量表、品种评价。小区的值取每个项目最近一次录的。
 *
 * 封面、基本情况、气象资料的字段和客户端 client/src/shared/recordBook.ts 一致。
 */
@Service
public class RecordBookExport {

    private static final String[] ROMAN = {"Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ"};
    private static final Pattern GRADE = Pattern.compile("^\\s*(\\d{1,2})\\s*级");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 四、田间记载的列：表头、单位行、项目编码（"plot"、"variety" 是小区号和品种编号） */
    private static final String[][] FIELD_COLUMNS = {
            {"区号", "", "plot"}, {"品种编号", "", "variety"},
            {"出苗期", "YY-MM-DD", "emergence_date"}, {"抽穗期", "YY-MM-DD", "heading_date"},
            {"成熟期", "YY-MM-DD", "maturity_date"}, {"习性", "", "seedling_habit"},
            {"基本苗", "(万/亩)", "basic_seedlings"}, {"最高分蘖", "(万/亩)", "max_tillers"},
            {"亩成穗", "(万/亩)", "effective_spikes"}, {"株高", "(cm)", "plant_height"},
            {"冬冻日期", "YY-MM-DD", "winter_freeze_date"}, {"冬冻程度", "", "winter_freeze_grade"},
            {"春冻日期", "YY-MM-DD", "spring_freeze_date"}, {"春冻程度", "", "spring_freeze_grade"},
            {"越冬率", "(%)", "overwinter_rate"}, {"旱害日期", "YY-MM-DD", "drought_date"},
            {"旱害程度", "", "drought_grade"}, {"青干日期", "YY-MM-DD", "dry_hot_date"},
            {"青干程度", "", "dry_hot_grade"}, {"湿害日期", "YY-MM-DD", "wet_date"},
            {"湿害程度", "", "wet_grade"}, {"第1次倒伏日期", "YY-MM-DD", "lodging_first_date"},
            {"倒伏程度", "", "lodging_first_grade"}, {"倒伏面积", "%", "lodging_first_area"},
            {"第2次倒伏日期", "YY-MM-DD", "lodging_final_date"}, {"倒伏程度", "", "lodging_final_grade"},
            {"倒伏面积", "%", "lodging_final_area"}, {"条锈病反", "", "stripe_rust_reaction"},
            {"条锈病严", "%", "stripe_rust_severity"}, {"条锈病普", "%", "stripe_rust_incidence"},
            {"白粉病", "", "powdery_mildew"}, {"叶锈病反", "", "leaf_rust_reaction"},
            {"叶锈病严", "%", "leaf_rust_severity"}, {"叶锈病普", "%", "leaf_rust_incidence"},
            {"赤霉严重度", "", "scab_severity"}, {"赤霉病穗率", "%", "scab_spike_rate"},
            {"纹枯病", "", "sharp_eyespot"}, {"纹枯病穗", "%", "sharp_eyespot_whitehead"},
            {"根腐病穗部", "", "root_rot_spike"}, {"叶枯病", "", "leaf_blight"},
            {"穗发芽", "", "sprouting_grade"}, {"落粒性", "", "shattering"},
            {"熟相", "", "maturity_appearance"}, {"小区杂株数", "", "off_type_count"},
            {"小区产量", "(kg)", "plot_yield"}, {"田间评价", "", "field_evaluation"},
    };

    /** 六、室内考种：按品种一行；数值项目分重复列出再求平均，文字项目取第一个有值的 */
    private static final String[][] LAB_TEXT_BEFORE = {{"穗型", "spike_shape"}, {"壳色", "glume_color"}, {"芒", "awn"}};
    private static final String[][] LAB_TEXT_AFTER = {{"粒色", "grain_color"}, {"饱满度", "plumpness"},
            {"籽粒品质", "grain_texture"}};
    private static final String[][] LAB_NUMBERS = {{"每穗粒数(粒)", "grains_per_spike"}, {"黑胚率(%)", "black_germ_rate"},
            {"千粒重(克)", "thousand_grain_weight"}, {"容重(克)", "test_weight"}, {"杂株率", "off_type_rate"}};

    private static final String[][] COVER = {{"year", "年度"}, {"trialType", "试验区别"}, {"trialGroup", "试验组别"},
            {"unitName", "承试单位"}, {"location", "试验地点"}, {"altitude", "海拔(m)"}, {"lat", "北纬"},
            {"lng", "东经"}, {"leader", "负责人"}, {"recorder", "记载人"}};
    private static final String[][] DESIGN = {{"varietyCount", "供试品种数(个)"}, {"commonCK", "共同对照品种"},
            {"auxCK", "辅助对照品种名称"}, {"reps", "重复次数"}, {"plotLength", "小区长度(米)"},
            {"plotWidth", "小区宽度(米)"}, {"rowsPerPlot", "每小区行数(行)"}, {"rowSpacing", "行距(厘米)"},
            {"fieldArea", "试验田面积(亩)"}, {"density", "密度(万苗/亩)"}};
    private static final String[][] CULTIVATION = {{"prevCrop", "1、前茬"}, {"soil", "土质"}, {"irrigated", "水、旱地"},
            {"baseFert", "2、基肥（种类、数量、施肥时间及方法）"}, {"tillage", "3、整地（时间、机具、质量）"},
            {"seedFert", "4、种肥（种类、数量、施肥时间及方法）"}, {"seedTreat", "5、种子处理"},
            {"sowingDate", "6、播种期"}, {"sowingMethod", "播种方法"},
            {"topDress", "7、追肥（种类、数量、质量、施用时间及方法）"}, {"weeding", "8、中耕除草（时间、次数、方法和质量）"},
            {"irrigation", "9、灌溉（时间、次数、方法）"}, {"pest", "10、病虫害防治（对象、时间、药剂名称和方法）"},
            {"harvestDate", "11、收获期"}, {"other", "12、其它"}};
    private static final String[][] YIELD_PARAMS = {{"harvestLength", "收获长度(m)"}, {"plannedWidth", "规划小区宽(m)"},
            {"rowsPerPlot", "小区行数"}, {"rowSpacing", "行距(cm)"}, {"ridgeWidth", "畦埂宽(cm)"},
            {"harvestArea", "收获面积(m²，直接填的)"}};
    private static final String[][] CONTACT = {{"testUnit", "试验单位"}, {"contact", "负责人"}, {"phone", "联系电话"}};

    private static final int[] MONTHS = {9, 10, 11, 12, 1, 2, 3, 4, 5, 6};
    /** 气象资料表的行：键、分组、名称 */
    private static final String[][] WEATHER = {
            {"t0_avg", "上旬气温(℃)", "平均气温"}, {"t0_max", "上旬气温(℃)", "最高气温"}, {"t0_min", "上旬气温(℃)", "最低气温"},
            {"t1_avg", "中旬气温(℃)", "平均气温"}, {"t1_max", "中旬气温(℃)", "最高气温"}, {"t1_min", "中旬气温(℃)", "最低气温"},
            {"t2_avg", "下旬气温(℃)", "平均气温"}, {"t2_max", "下旬气温(℃)", "最高气温"}, {"t2_min", "下旬气温(℃)", "最低气温"},
            {"tMonth", "月平均气温(℃)", ""}, {"p0", "降水(mm)", "上旬"}, {"p1", "降水(mm)", "中旬"}, {"p2", "降水(mm)", "下旬"},
            {"pMonth", "月降水总量(mm)", ""}, {"pDays", "月降水天数(天)", ""}, {"pMax", "月最大降水量(mm)", ""},
            {"s0", "日照(小时)", "上旬"}, {"s1", "日照(小时)", "中旬"}, {"s2", "日照(小时)", "下旬"},
            {"sMonth", "月日照时数(小时)", ""},
    };

    private final TrialContextLoader contextLoader;
    private final ObservationMapper observationMapper;
    private final TraitCatalog catalog;
    private final TrialBookService bookService;
    private final FieldService fieldService;

    public RecordBookExport(TrialContextLoader contextLoader, ObservationMapper observationMapper, TraitCatalog catalog,
                            TrialBookService bookService, FieldService fieldService) {
        this.contextLoader = contextLoader;
        this.observationMapper = observationMapper;
        this.catalog = catalog;
        this.bookService = bookService;
        this.fieldService = fieldService;
    }

    /** 一个试验小区：编号、重复号、品种，和每个项目编码最近一次录的值 */
    private record PlotRow(Plot plot, Planting planting, Variety variety, Map<String, Observation> latest) {
    }

    public String filename(Long trialId) {
        TrialContext ctx = contextLoader.load(trialId);
        Field field = fieldService.get(ctx.trial().getFieldId());
        return field.getName() + "-" + ctx.trial().getSeasonYear() + "-记载本.xlsx";
    }

    public byte[] export(Long trialId) {
        TrialContext ctx = contextLoader.load(trialId);
        TrialBookView book = bookService.get(trialId);
        Field field = fieldService.get(ctx.trial().getFieldId());
        Map<Long, TraitDef> traitsById = catalog.all();
        Map<String, TraitDef> traits = new HashMap<>();
        traitsById.values().forEach(t -> traits.put(t.getCode(), t));
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        List<PlotRow> rows = plotRows(ctx, traitsById);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            String year = book.info().getOrDefault("year",
                    (ctx.trial().getSeasonYear() - 1) + "-" + ctx.trial().getSeasonYear());
            cover(wb.createSheet("封面"), st, book.info(), year);
            plantingMap(wb.createSheet("田间种植图"), st, rows, year);
            basics(wb.createSheet("基本情况"), st, book.info(), book.harvestAreaM2());
            weather(wb.createSheet("气象资料"), st, book.weather(), year);
            fieldRecords(wb.createSheet("田间记载"), st, rows, traits, options, ctx);
            lab(wb.createSheet("考种结果"), st, rows, traits, options);
            yieldTable(wb.createSheet("产量表"), st, rows, traits, book);
            evaluation(wb.createSheet("品种评价"), st, rows, book);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<PlotRow> plotRows(TrialContext ctx, Map<Long, TraitDef> traitsById) {
        Map<Long, Map<String, Observation>> latestByPlanting = new HashMap<>();
        if (!ctx.plantingsById().isEmpty()) {
            List<Observation> all = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                    .in(Observation::getPlantingId, ctx.plantingsById().keySet())
                    .eq(Observation::getDeleted, false)
                    .orderByDesc(Observation::getObservedOn).orderByDesc(Observation::getId));
            for (Observation o : all) {
                TraitDef t = traitsById.get(o.getTraitId());
                if (t != null) {
                    latestByPlanting.computeIfAbsent(o.getPlantingId(), k -> new HashMap<>()).putIfAbsent(t.getCode(), o);
                }
            }
        }
        List<PlotRow> rows = new ArrayList<>();
        for (Planting p : ctx.plantingsById().values()) {
            Plot plot = ctx.plots().get(p.getPlotId());
            if (plot == null || !Plot.KIND_PLOT.equals(plot.getKind())) {
                continue;
            }
            rows.add(new PlotRow(plot, p, ctx.varietyOf(p), latestByPlanting.getOrDefault(p.getId(), Map.of())));
        }
        rows.sort(Comparator.comparing((PlotRow r) -> r.planting().getReplicateNo() == null ? 99 : r.planting().getReplicateNo())
                .thenComparing(r -> codeKey(r.plot())));
        return rows;
    }

    /** 小区编号按数字排（101 在 99 后面），不是数字的排后面 */
    private static String codeKey(Plot p) {
        String c = p.getCode() == null ? "" : p.getCode();
        return c.matches("\\d+") ? String.format("%012d", Long.parseLong(c.length() > 12 ? c.substring(0, 12) : c)) : "~" + c;
    }

    private static String rep(Integer n) {
        return n == null ? "未设重复" : n >= 1 && n <= ROMAN.length ? ROMAN[n - 1] : String.valueOf(n);
    }

    private static String varietyCode(Variety v) {
        if (v == null) {
            return null;
        }
        return v.getCode() != null && !v.getCode().isBlank() ? v.getCode() : v.getName();
    }

    // ---------- 各张表 ----------

    private void cover(Sheet s, Styles st, Map<String, String> info, String year) {
        s.setColumnWidth(0, 4 * 256);
        s.setColumnWidth(1, 16 * 256);
        s.setColumnWidth(2, 40 * 256);
        title(s, st, 1, "河南省小麦品种试验记载本", 3);
        Row y = s.createRow(3);
        cell(y, 1, year + " 年度", st.subtitle);
        s.addMergedRegion(new CellRangeAddress(3, 3, 1, 2));
        int r = 5;
        for (String[] f : COVER) {
            if ("year".equals(f[0])) {
                continue;
            }
            Row row = s.createRow(r++);
            row.setHeightInPoints(22);
            cell(row, 1, f[1] + "：", st.label);
            cell(row, 2, info.get(f[0]), st.underline);
        }
    }

    private void plantingMap(Sheet s, Styles st, List<PlotRow> rows, String year) {
        Map<String, List<PlotRow>> byRep = byReplicate(rows);
        int col = 0;
        title(s, st, 0, year + "年度试点田间种植图", Math.max(2, byRep.size() * 2));
        Row h1 = s.createRow(1);
        Row h2 = s.createRow(2);
        for (Map.Entry<String, List<PlotRow>> e : byRep.entrySet()) {
            cell(h1, col, "重复" + e.getKey(), st.header);
            cell(h1, col + 1, null, st.header);
            s.addMergedRegion(new CellRangeAddress(1, 1, col, col + 1));
            cell(h2, col, "小区号", st.header);
            cell(h2, col + 1, "品种名", st.header);
            int r = 3;
            for (PlotRow p : e.getValue()) {
                Row row = rowAt(s, r++);
                cell(row, col, p.plot().getCode(), st.center);
                cell(row, col + 1, p.variety() == null ? null : p.variety().getName(), st.text);
            }
            s.setColumnWidth(col, 10 * 256);
            s.setColumnWidth(col + 1, 16 * 256);
            col += 2;
        }
        s.createFreezePane(0, 3);
    }

    private void basics(Sheet s, Styles st, Map<String, String> info, BigDecimal harvestArea) {
        s.setColumnWidth(0, 42 * 256);
        s.setColumnWidth(1, 60 * 256);
        int r = 0;
        r = section(s, st, r, "一、试验设计", DESIGN, info);
        r = section(s, st, r + 1, "二、栽培管理", CULTIVATION, info);
        r = section(s, st, r + 1, "产量计算参数", YIELD_PARAMS, info);
        Row area = s.createRow(r++);
        cell(area, 0, "计产用的收获面积(m²)", st.label);
        cell(area, 1, harvestArea, st.number);
        Row note = s.createRow(r++);
        cell(note, 0, "收获面积 = 收获长 ×［(小区行数 − 1) × 行距 + 畦埂宽］", st.note);
        s.addMergedRegion(new CellRangeAddress(note.getRowNum(), note.getRowNum(), 0, 1));
        section(s, st, r + 1, "联系信息", CONTACT, info);
    }

    private int section(Sheet s, Styles st, int r, String name, String[][] fields, Map<String, String> info) {
        Row head = s.createRow(r++);
        cell(head, 0, name, st.section);
        for (String[] f : fields) {
            Row row = s.createRow(r++);
            cell(row, 0, f[1], st.label);
            String v = info.get(f[0]);
            BigDecimal n = number(v);
            cell(row, 1, n != null && !f[0].endsWith("Date") ? n : v, n != null ? st.number : st.wrap);
        }
        return r;
    }

    private void weather(Sheet s, Styles st, Map<String, String> w, String year) {
        title(s, st, 0, "三、" + year + "年度小麦生育期间气象资料表", 2 + MONTHS.length * 2);
        Row h1 = s.createRow(1);
        Row h2 = s.createRow(2);
        cell(h1, 0, "项目", st.header);
        cell(h1, 1, null, st.header);
        cell(h2, 0, null, st.header);
        cell(h2, 1, null, st.header);
        s.addMergedRegion(new CellRangeAddress(1, 2, 0, 1));
        for (int i = 0; i < MONTHS.length; i++) {
            int c = 2 + i * 2;
            cell(h1, c, MONTHS[i] + "月", st.header);
            cell(h1, c + 1, null, st.header);
            s.addMergedRegion(new CellRangeAddress(1, 1, c, c + 1));
            cell(h2, c, "当年", st.header);
            cell(h2, c + 1, "常年", st.header);
            s.setColumnWidth(c, 7 * 256);
            s.setColumnWidth(c + 1, 7 * 256);
        }
        s.setColumnWidth(0, 16 * 256);
        s.setColumnWidth(1, 10 * 256);
        int r = 3;
        for (String[] row : WEATHER) {
            Row x = s.createRow(r++);
            cell(x, 0, row[1], st.label);
            cell(x, 1, row[2], st.label);
            for (int i = 0; i < MONTHS.length; i++) {
                cell(x, 2 + i * 2, number(w.get(row[0] + "|" + MONTHS[i] + "|cur")), st.number);
                cell(x, 3 + i * 2, number(w.get(row[0] + "|" + MONTHS[i] + "|norm")), st.number);
            }
        }
    }

    private void fieldRecords(Sheet s, Styles st, List<PlotRow> rows, Map<String, TraitDef> traits,
                              Map<Long, List<TraitOption>> options, TrialContext ctx) {
        int r = 0;
        int n = 1;
        for (Map.Entry<String, List<PlotRow>> e : byReplicate(rows).entrySet()) {
            Row t = s.createRow(r);
            cell(t, 0, "四-" + n++ + "、田间记载（" + e.getKey() + "重复）", st.section);
            s.addMergedRegion(new CellRangeAddress(r, r, 0, FIELD_COLUMNS.length - 1));
            Row head = s.createRow(r + 1);
            Row unit = s.createRow(r + 2);
            for (int c = 0; c < FIELD_COLUMNS.length; c++) {
                cell(head, c, FIELD_COLUMNS[c][0], st.header);
                cell(unit, c, FIELD_COLUMNS[c][1], st.unit);
            }
            r += 3;
            for (PlotRow p : e.getValue()) {
                Row row = s.createRow(r++);
                for (int c = 0; c < FIELD_COLUMNS.length; c++) {
                    String code = FIELD_COLUMNS[c][2];
                    Object v = switch (code) {
                        case "plot" -> p.plot().getCode();
                        case "variety" -> varietyCode(p.variety());
                        default -> value(traits.get(code), options, p, ctx);
                    };
                    cell(row, c, v, v instanceof LocalDate ? st.date : v instanceof BigDecimal ? st.number : st.text);
                }
            }
            r++;
        }
        for (int c = 0; c < FIELD_COLUMNS.length; c++) {
            s.setColumnWidth(c, (c == FIELD_COLUMNS.length - 1 ? 24 : c < 2 ? 9 : 10) * 256);
        }
    }

    /** 小区某个项目最近一次的值：日期、数字、分级数字（记载本用 1、3、5 记的选项写级别），或文字 */
    private Object value(TraitDef t, Map<Long, List<TraitOption>> options, PlotRow p, TrialContext ctx) {
        if (t == null) {
            return null;
        }
        if (t.getIsComputed() != null && t.getIsComputed()) {
            BigDecimal v = ComputedTraits.compute(t, p.latest(), ctx.plots().get(p.plot().getId()));
            return round(v, t.getPrecisionDigits());
        }
        Observation o = p.latest().get(t.getCode());
        if (o == null) {
            return null;
        }
        if (o.getValueDate() != null) {
            return o.getValueDate();
        }
        if (o.getValueText() != null) {
            for (TraitOption opt : options.getOrDefault(t.getId(), List.of())) {
                if (opt.getValueCode().equals(o.getValueText())) {
                    Matcher m = opt.getDescription() == null ? null : GRADE.matcher(opt.getDescription());
                    return m != null && m.find() ? new BigDecimal(m.group(1)) : opt.getLabel();
                }
            }
            return o.getValueText();
        }
        return round(o.getValueNum(), t.getPrecisionDigits());
    }

    private void lab(Sheet s, Styles st, List<PlotRow> rows, Map<String, TraitDef> traits,
                     Map<Long, List<TraitOption>> options) {
        int reps = replicateCount(rows);
        List<String> headers = new ArrayList<>(List.of("品种(系)名称"));
        for (String[] f : LAB_TEXT_BEFORE) {
            headers.add(f[0]);
        }
        List<int[]> numberCols = new ArrayList<>();
        for (int i = 0; i < LAB_NUMBERS.length; i++) {
            if (i == 1) {
                for (String[] f : LAB_TEXT_AFTER) {
                    headers.add(f[0]);
                }
            }
            numberCols.add(new int[]{headers.size(), i});
            for (int k = 0; k < reps; k++) {
                headers.add(LAB_NUMBERS[i][0]);
            }
            headers.add(LAB_NUMBERS[i][0]);
        }
        title(s, st, 0, "六、室内考种结果", headers.size());
        Row h1 = s.createRow(1);
        Row h2 = s.createRow(2);
        for (int c = 0; c < headers.size(); c++) {
            cell(h1, c, headers.get(c), st.header);
            cell(h2, c, null, st.header);
        }
        for (int c = 0; c < headers.size(); c++) {
            final int col = c;
            boolean grouped = numberCols.stream().anyMatch(nc -> col >= nc[0] && col <= nc[0] + reps);
            if (!grouped) {
                s.addMergedRegion(new CellRangeAddress(1, 2, c, c));
            }
        }
        for (int[] nc : numberCols) {
            s.addMergedRegion(new CellRangeAddress(1, 1, nc[0], nc[0] + reps));
            for (int k = 0; k < reps; k++) {
                cell(h2, nc[0] + k, "第" + ROMAN[k] + "重复", st.header);
            }
            cell(h2, nc[0] + reps, "平均", st.header);
        }
        int r = 3;
        for (Map.Entry<Variety, List<PlotRow>> e : byVariety(rows).entrySet()) {
            Row row = s.createRow(r++);
            int c = 0;
            cell(row, c++, e.getKey().getName(), st.text);
            for (String[] f : LAB_TEXT_BEFORE) {
                cell(row, c++, firstText(e.getValue(), traits.get(f[1]), options), st.text);
            }
            for (int i = 0; i < LAB_NUMBERS.length; i++) {
                if (i == 1) {
                    for (String[] f : LAB_TEXT_AFTER) {
                        cell(row, c++, firstText(e.getValue(), traits.get(f[1]), options), st.text);
                    }
                }
                TraitDef t = traits.get(LAB_NUMBERS[i][1]);
                List<BigDecimal> values = new ArrayList<>();
                for (int k = 1; k <= reps; k++) {
                    BigDecimal v = repValue(e.getValue(), k, t);
                    cell(row, c++, v, st.number);
                    if (v != null) {
                        values.add(v);
                    }
                }
                cell(row, c++, mean(values, t == null ? 1 : t.getPrecisionDigits()), st.number);
            }
        }
        for (int c = 0; c < headers.size(); c++) {
            s.setColumnWidth(c, (c == 0 ? 14 : 9) * 256);
        }
        s.createFreezePane(1, 3);
    }

    private void yieldTable(Sheet s, Styles st, List<PlotRow> rows, Map<String, TraitDef> traits, TrialBookView book) {
        int reps = replicateCount(rows);
        TraitDef plotYield = traits.get("plot_yield");
        TraitDef perMu = traits.get("yield_per_mu");
        title(s, st, 0, "九、产量表", 4 + reps * 2 + 2);
        Row h1 = s.createRow(1);
        Row h2 = s.createRow(2);
        int c = 0;
        cell(h1, c, "品种代号", st.header);
        cell(h2, c, null, st.header);
        s.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
        c = 1;
        for (String g : new String[]{"小区产量（kg）", "折亩产（kg）"}) {
            cell(h1, c, g, st.header);
            for (int k = 1; k <= reps; k++) {
                cell(h1, c + k, null, st.header);
            }
            s.addMergedRegion(new CellRangeAddress(1, 1, c, c + reps));
            for (int k = 0; k < reps; k++) {
                cell(h2, c + k, ROMAN[k], st.header);
            }
            cell(h2, c + reps, "平均", st.header);
            c += reps + 1;
        }
        cell(h1, c, "±%", st.header);
        cell(h2, c, null, st.header);
        s.addMergedRegion(new CellRangeAddress(1, 2, c, c));
        cell(h1, c + 1, "位次", st.header);
        cell(h2, c + 1, null, st.header);
        s.addMergedRegion(new CellRangeAddress(1, 2, c + 1, c + 1));

        // 先算各品种平均亩产、试验平均、位次
        Map<Variety, List<PlotRow>> byVariety = byVariety(rows);
        Map<Variety, BigDecimal> muMean = new LinkedHashMap<>();
        for (Map.Entry<Variety, List<PlotRow>> e : byVariety.entrySet()) {
            List<BigDecimal> vs = new ArrayList<>();
            for (int k = 1; k <= reps; k++) {
                BigDecimal v = repValue(e.getValue(), k, perMu);
                if (v != null) {
                    vs.add(v);
                }
            }
            muMean.put(e.getKey(), mean(vs, 1));
        }
        List<BigDecimal> means = muMean.values().stream().filter(Objects::nonNull).toList();
        BigDecimal trialMean = mean(means, 2);
        List<Variety> ranked = muMean.entrySet().stream().filter(e -> e.getValue() != null)
                .sorted(Map.Entry.<Variety, BigDecimal>comparingByValue().reversed()).map(Map.Entry::getKey).toList();

        int r = 3;
        List<Variety> order = new ArrayList<>(ranked);
        byVariety.keySet().stream().filter(v -> !order.contains(v)).forEach(order::add);
        for (Variety v : order) {
            List<PlotRow> plots = byVariety.get(v);
            Row row = s.createRow(r++);
            cell(row, 0, varietyCode(v), st.text);
            int col = 1;
            for (TraitDef t : new TraitDef[]{plotYield, perMu}) {
                List<BigDecimal> vs = new ArrayList<>();
                for (int k = 1; k <= reps; k++) {
                    BigDecimal x = repValue(plots, k, t);
                    cell(row, col++, x, st.number);
                    if (x != null) {
                        vs.add(x);
                    }
                }
                cell(row, col++, mean(vs, t == plotYield ? 2 : 1), st.number);
            }
            BigDecimal m = muMean.get(v);
            BigDecimal pct = m == null || trialMean == null || trialMean.signum() == 0 ? null
                    : m.subtract(trialMean).multiply(HUNDRED).divide(trialMean, 1, RoundingMode.HALF_UP);
            cell(row, col++, pct, st.number);
            int rank = ranked.indexOf(v);
            cell(row, col, rank < 0 ? null : BigDecimal.valueOf(rank + 1), st.number);
        }
        r++;
        Row note = s.createRow(r++);
        cell(note, 0, "注：±% 以试验平均产量为基础计算", st.note);
        s.addMergedRegion(new CellRangeAddress(note.getRowNum(), note.getRowNum(), 0, 6));
        Map<String, String> info = book.info();
        for (String[] f : YIELD_PARAMS) {
            Row row = s.createRow(r++);
            cell(row, 0, f[1], st.label);
            s.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 2));
            cell(row, 3, number(info.get(f[0])), st.number);
        }
        Row area = s.createRow(r);
        cell(area, 0, "计产用的收获面积(m²)", st.label);
        s.addMergedRegion(new CellRangeAddress(area.getRowNum(), area.getRowNum(), 0, 2));
        cell(area, 3, book.harvestAreaM2(), st.number);
        s.setColumnWidth(0, 14 * 256);
        for (int k = 1; k <= c + 1; k++) {
            s.setColumnWidth(k, 9 * 256);
        }
        s.createFreezePane(1, 3);
    }

    private void evaluation(Sheet s, Styles st, List<PlotRow> rows, TrialBookView book) {
        s.setColumnWidth(0, 100 * 256);
        Row t = s.createRow(0);
        cell(t, 0, "十、品种评价", st.section);
        int r = 1;
        Row wh = s.createRow(r++);
        cell(wh, 0, "气象分析", st.label);
        Row wa = s.createRow(r++);
        cell(wa, 0, book.info().get("weatherAnalysis"), st.wrap);
        for (Variety v : byVariety(rows).keySet()) {
            Row h = s.createRow(r++);
            cell(h, 0, v.getName() + (v.getControlType() != null ? "（对照）" : ""), st.label);
            Row b = s.createRow(r++);
            cell(b, 0, book.evaluations().get(String.valueOf(v.getId())), st.wrap);
        }
    }

    // ---------- 小工具 ----------

    private static Map<String, List<PlotRow>> byReplicate(List<PlotRow> rows) {
        Map<Integer, List<PlotRow>> sorted = new TreeMap<>();
        for (PlotRow r : rows) {
            Integer n = r.planting().getReplicateNo();
            sorted.computeIfAbsent(n == null ? 999 : n, k -> new ArrayList<>()).add(r);
        }
        Map<String, List<PlotRow>> out = new LinkedHashMap<>();
        sorted.forEach((n, list) -> out.put(rep(n == 999 ? null : n), list));
        return out;
    }

    /** 种了品种的小区按品种分组：对照在前，其余按名称 */
    private static Map<Variety, List<PlotRow>> byVariety(List<PlotRow> rows) {
        Map<Variety, List<PlotRow>> out = new TreeMap<>(Comparator
                .comparing((Variety v) -> v.getControlType() == null)
                .thenComparing(Variety::getName)
                .thenComparing(Variety::getId));
        for (PlotRow r : rows) {
            if (r.variety() != null) {
                out.computeIfAbsent(r.variety(), k -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    private static int replicateCount(List<PlotRow> rows) {
        int max = rows.stream().map(r -> r.planting().getReplicateNo()).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(1);
        return Math.min(Math.max(max, 1), ROMAN.length);
    }

    /** 某个品种第 k 个重复的值（同一重复有几个小区时取平均） */
    private BigDecimal repValue(List<PlotRow> plots, int k, TraitDef t) {
        if (t == null) {
            return null;
        }
        List<BigDecimal> vs = new ArrayList<>();
        for (PlotRow p : plots) {
            if (Objects.equals(p.planting().getReplicateNo(), k)) {
                BigDecimal v = t.getIsComputed() != null && t.getIsComputed()
                        ? ComputedTraits.compute(t, p.latest(), p.plot())
                        : p.latest().get(t.getCode()) == null ? null : p.latest().get(t.getCode()).getValueNum();
                if (v != null) {
                    vs.add(v);
                }
            }
        }
        return mean(vs, t.getPrecisionDigits());
    }

    private static String firstText(List<PlotRow> plots, TraitDef t, Map<Long, List<TraitOption>> options) {
        if (t == null) {
            return null;
        }
        for (PlotRow p : plots) {
            Observation o = p.latest().get(t.getCode());
            if (o != null && o.getValueText() != null) {
                return options.getOrDefault(t.getId(), List.of()).stream()
                        .filter(x -> x.getValueCode().equals(o.getValueText())).map(TraitOption::getLabel)
                        .findFirst().orElse(o.getValueText());
            }
        }
        return null;
    }

    private static BigDecimal mean(List<BigDecimal> values, Integer scale) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), scale == null ? 2 : scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal v, Integer scale) {
        return v == null || scale == null ? v : v.setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Row rowAt(Sheet s, int r) {
        Row row = s.getRow(r);
        return row == null ? s.createRow(r) : row;
    }

    private static void title(Sheet s, Styles st, int r, String text, int span) {
        Row row = rowAt(s, r);
        row.setHeightInPoints(28);
        cell(row, 0, text, st.title);
        if (span > 1) {
            s.addMergedRegion(new CellRangeAddress(r, r, 0, span - 1));
        }
    }

    private static void cell(Row row, int c, Object v, CellStyle style) {
        Cell cell = row.createCell(c);
        switch (v) {
            case null -> cell.setBlank();
            case BigDecimal d -> cell.setCellValue(d.doubleValue());
            case LocalDate d -> cell.setCellValue(d);
            default -> cell.setCellValue(v.toString());
        }
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle section;
        final CellStyle header;
        final CellStyle unit;
        final CellStyle label;
        final CellStyle text;
        final CellStyle wrap;
        final CellStyle center;
        final CellStyle number;
        final CellStyle date;
        final CellStyle underline;
        final CellStyle note;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 16);
            Font mid = wb.createFont();
            mid.setBold(true);
            mid.setFontHeightInPoints((short) 13);
            Font small = wb.createFont();
            small.setFontHeightInPoints((short) 9);
            small.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            title = wb.createCellStyle();
            title.setFont(big);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);
            subtitle = wb.createCellStyle();
            subtitle.setFont(mid);
            subtitle.setAlignment(HorizontalAlignment.CENTER);
            section = wb.createCellStyle();
            section.setFont(mid);
            header = bordered(wb);
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            header.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            unit = bordered(wb);
            unit.setFont(small);
            unit.setAlignment(HorizontalAlignment.CENTER);
            label = bordered(wb);
            label.setFont(bold);
            label.setVerticalAlignment(VerticalAlignment.TOP);
            label.setWrapText(true);
            text = bordered(wb);
            wrap = bordered(wb);
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            center = bordered(wb);
            center.setAlignment(HorizontalAlignment.CENTER);
            number = bordered(wb);
            number.setDataFormat(wb.createDataFormat().getFormat("General"));
            date = bordered(wb);
            date.setDataFormat(wb.createDataFormat().getFormat("yy-mm-dd"));
            date.setAlignment(HorizontalAlignment.CENTER);
            underline = wb.createCellStyle();
            underline.setBorderBottom(BorderStyle.THIN);
            note = wb.createCellStyle();
            note.setFont(small);
        }

        private static CellStyle bordered(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }
}
