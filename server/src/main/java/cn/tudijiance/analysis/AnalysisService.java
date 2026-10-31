package cn.tudijiance.analysis;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.analysis.AnalysisDtos.Agg;
import cn.tudijiance.analysis.AnalysisDtos.AnalysisQuery;
import cn.tudijiance.analysis.AnalysisDtos.AnalysisResult;
import cn.tudijiance.analysis.AnalysisDtos.Column;
import cn.tudijiance.analysis.AnalysisDtos.Dimension;
import cn.tudijiance.analysis.AnalysisDtos.DimensionSpec;
import cn.tudijiance.analysis.AnalysisDtos.MeasureSpec;
import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldMapper;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ComputedTraits;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialMapper;
import cn.tudijiance.variety.Variety;

/**
 * 自助分析：老师选范围（实验田、年份、品种、重复、小区）、分组方式（最多两个）和要统计的项目，
 * 这里按"每个小区每年一条记录"取值（录入项目取最近一次的值，计算项目现算），再分组汇总。
 */
@Service
public class AnalysisService {

    static final int MAX_ROWS = 5000;
    private static final Set<String> CATEGORICAL_TYPES = Set.of("GRADE", "OPTION", "OPERATION", "TEXT");
    private static final Map<Agg, String> AGG_NAMES = Map.of(Agg.AVG, "平均", Agg.SUM, "合计", Agg.MAX, "最大",
            Agg.MIN, "最小", Agg.MEDIAN, "中位数", Agg.COUNT, "个数", Agg.CV, "变异系数");
    private static final Map<Dimension, String> DIMENSION_NAMES = Map.of(Dimension.VARIETY, "品种",
            Dimension.YEAR, "年份", Dimension.REPLICATE, "重复", Dimension.PLOT, "小区", Dimension.FIELD, "实验田");
    private static final Collator CHINESE = Collator.getInstance(Locale.CHINA);

    private final TrialMapper trialMapper;
    private final FieldMapper fieldMapper;
    private final TrialContextLoader contextLoader;
    private final ComputedTraits computedTraits;
    private final TraitCatalog catalog;

    public AnalysisService(TrialMapper trialMapper, FieldMapper fieldMapper, TrialContextLoader contextLoader,
                           ComputedTraits computedTraits, TraitCatalog catalog) {
        this.trialMapper = trialMapper;
        this.fieldMapper = fieldMapper;
        this.contextLoader = contextLoader;
        this.computedTraits = computedTraits;
        this.catalog = catalog;
    }

    /** 统计的项目；trait 为 null 表示数小区个数 */
    private record Measure(TraitDef trait, Agg agg) {
    }

    /** 分组方式；trait 只在按项目取值分组时有 */
    private record Dim(Dimension type, TraitDef trait, List<TraitOption> options) {
    }

    /** 一条记录：某一年某个小区（一个种植记录） */
    private record Rec(Trial trial, String fieldName, Planting planting, Plot plot, Variety variety,
                       Map<String, Observation> latest) {
    }

    /** 分组的一个取值：显示的名称和排序用的值 */
    private record Key(String label, Comparable<?> sort) {
    }

    public AnalysisResult query(AnalysisQuery q) {
        List<Measure> measures = q.measures().stream().map(this::measure).toList();
        List<Dim> dims = q.dimensions().stream().map(this::dimension).toList();
        if (dims.stream().map(d -> d.type() + "|" + (d.trait() == null ? "" : d.trait().getId())).distinct().count() < dims.size()) {
            throw ApiException.badRequest("两个分组方式不能相同");
        }
        List<Rec> recs = records(q, measures, dims);
        return dims.isEmpty() ? detail(recs, measures) : grouped(recs, dims, measures);
    }

    // ---------- 校验 ----------

    private Measure measure(MeasureSpec spec) {
        if (spec.traitId() == null) {
            if (spec.agg() != Agg.COUNT) {
                throw ApiException.badRequest("请选择要统计的项目");
            }
            return new Measure(null, Agg.COUNT);
        }
        TraitDef trait = catalog.get(spec.traitId());
        if (spec.agg() != Agg.COUNT && !ComputedTraits.isNumeric(trait)) {
            throw ApiException.badRequest(trait.getName() + "不是数值，只能统计个数");
        }
        return new Measure(trait, spec.agg());
    }

    private Dim dimension(DimensionSpec spec) {
        if (spec.type() != Dimension.TRAIT) {
            return new Dim(spec.type(), null, List.of());
        }
        if (spec.traitId() == null) {
            throw ApiException.badRequest("请选择按哪个项目分组");
        }
        TraitDef trait = catalog.get(spec.traitId());
        if (!CATEGORICAL_TYPES.contains(trait.getDataType())) {
            throw ApiException.badRequest(trait.getName() + "不能用来分组，只能按分级、选项或文字类项目分组");
        }
        return new Dim(Dimension.TRAIT, trait, catalog.optionsOf(trait.getId()));
    }

    // ---------- 取数 ----------

    private List<Trial> trials(AnalysisQuery q) {
        var query = Wrappers.<Trial>lambdaQuery();
        if (q.trialIds() != null && !q.trialIds().isEmpty()) {
            query.in(Trial::getId, q.trialIds());
        } else {
            if (q.fieldId() != null) {
                query.eq(Trial::getFieldId, q.fieldId());
            }
            if (q.years() != null && !q.years().isEmpty()) {
                query.in(Trial::getSeasonYear, q.years());
            }
        }
        return trialMapper.selectList(query.orderByAsc(Trial::getSeasonYear).orderByAsc(Trial::getId));
    }

    private List<Rec> records(AnalysisQuery q, List<Measure> measures, List<Dim> dims) {
        List<Trial> trials = trials(q);
        if (trials.isEmpty()) {
            return List.of();
        }
        Map<Long, String> fieldNames = fieldMapper.selectByIds(trials.stream().map(Trial::getFieldId).distinct().toList())
                .stream().collect(Collectors.toMap(Field::getId, Field::getName));
        Set<Long> varietyIds = toSet(q.varietyIds());
        Set<Integer> replicates = toSet(q.replicates());
        Set<Long> plotIds = toSet(q.plotIds());

        List<Rec> recs = new ArrayList<>();
        for (Trial trial : trials) {
            TrialContext ctx = contextLoader.load(trial.getId());
            for (Planting p : ctx.plantingsById().values()) {
                Plot plot = ctx.plots().get(p.getPlotId());
                Variety variety = ctx.varietyOf(p);
                if (variety == null || plot == null || !Plot.KIND_PLOT.equals(plot.getKind())
                        || (!varietyIds.isEmpty() && !varietyIds.contains(variety.getId()))
                        || (!replicates.isEmpty() && !replicates.contains(p.getReplicateNo()))
                        || (!plotIds.isEmpty() && !plotIds.contains(plot.getId()))) {
                    continue;
                }
                recs.add(new Rec(trial, fieldNames.get(trial.getFieldId()), p, plot, variety, Map.of()));
            }
        }
        List<TraitDef> needed = new ArrayList<>();
        measures.stream().map(Measure::trait).filter(t -> t != null).forEach(needed::add);
        dims.stream().map(Dim::trait).filter(t -> t != null).forEach(needed::add);
        Map<Long, Map<String, Observation>> latest =
                computedTraits.latest(recs.stream().map(r -> r.planting().getId()).toList(), needed);
        return recs.stream()
                .map(r -> new Rec(r.trial(), r.fieldName(), r.planting(), r.plot(), r.variety(),
                        latest.getOrDefault(r.planting().getId(), Map.of())))
                .toList();
    }

    private static <T> Set<T> toSet(Collection<T> list) {
        return list == null ? Set.of() : new HashSet<>(list);
    }

    // ---------- 不分组：每条记录一行 ----------

    private AnalysisResult detail(List<Rec> recs, List<Measure> measures) {
        if (recs.size() > MAX_ROWS) {
            throw tooMany();
        }
        List<Column> columns = new ArrayList<>(List.of(
                new Column("year", "年份", null, "DIMENSION"),
                new Column("plot", "小区", null, "DIMENSION"),
                new Column("variety", "品种", null, "DIMENSION"),
                new Column("replicate", "重复", null, "DIMENSION")));
        columns.addAll(measureColumns(measures));
        List<List<Object>> rows = recs.stream()
                .sorted(Comparator.comparing((Rec r) -> r.trial().getSeasonYear())
                        .thenComparing(r -> r.plot().getRowNo()).thenComparing(r -> r.plot().getColNo()))
                .map(r -> {
                    List<Object> row = new ArrayList<>();
                    row.add(String.valueOf(r.trial().getSeasonYear()));
                    row.add(plotLabel(r.plot()));
                    row.add(r.variety().getName());
                    row.add(replicateLabel(r.planting().getReplicateNo()));
                    for (Measure m : measures) {
                        row.add(m.trait() == null ? BigDecimal.ONE : value(r, m.trait()));
                    }
                    return row;
                })
                .toList();
        return new AnalysisResult(columns, rows, recs.size());
    }

    // ---------- 分组汇总 ----------

    private AnalysisResult grouped(List<Rec> recs, List<Dim> dims, List<Measure> measures) {
        Map<List<Key>, List<Rec>> groups = new LinkedHashMap<>();
        for (Rec r : recs) {
            List<Key> key = new ArrayList<>(dims.size());
            for (Dim d : dims) {
                Key k = key(r, d);
                if (k == null) {
                    break;
                }
                key.add(k);
            }
            if (key.size() == dims.size()) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        if (groups.size() > MAX_ROWS) {
            throw tooMany();
        }
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            Dim d = dims.get(i);
            columns.add(new Column("d" + i, d.trait() == null ? DIMENSION_NAMES.get(d.type()) : d.trait().getName(),
                    null, "DIMENSION"));
        }
        columns.addAll(measureColumns(measures));
        Comparator<List<Key>> order = (a, b) -> {
            for (int i = 0; i < a.size(); i++) {
                int c = compare(a.get(i).sort(), b.get(i).sort());
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
        List<List<Object>> rows = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(order))
                .map(e -> {
                    List<Object> row = new ArrayList<>();
                    e.getKey().forEach(k -> row.add(k.label()));
                    for (Measure m : measures) {
                        row.add(aggregate(e.getValue(), m));
                    }
                    return row;
                })
                .toList();
        int used = groups.values().stream().mapToInt(List::size).sum();
        return new AnalysisResult(columns, rows, used);
    }

    /** 记录在这个分组方式上的取值；按项目分组而这条记录没有记这个项目时返回 null（不参与统计） */
    private static Key key(Rec r, Dim d) {
        return switch (d.type()) {
            case VARIETY -> new Key(r.variety().getName(), r.variety().getName());
            case YEAR -> new Key(String.valueOf(r.trial().getSeasonYear()), r.trial().getSeasonYear());
            case REPLICATE -> {
                Integer rep = r.planting().getReplicateNo();
                yield new Key(replicateLabel(rep), rep == null ? Integer.MAX_VALUE : rep);
            }
            case PLOT -> new Key(plotLabel(r.plot()), r.plot().getRowNo() * 10000 + r.plot().getColNo());
            case FIELD -> new Key(r.fieldName(), r.fieldName());
            case TRAIT -> traitKey(r.latest().get(d.trait().getCode()), d);
        };
    }

    private static Key traitKey(Observation o, Dim d) {
        if (o == null) {
            return null;
        }
        String code = switch (d.trait().getDataType()) {
            case "GRADE" -> o.getValueNum() == null ? null : o.getValueNum().stripTrailingZeros().toPlainString();
            default -> o.getValueText();
        };
        if (code == null) {
            return null;
        }
        for (TraitOption option : d.options()) {
            if (option.getValueCode().equals(code)) {
                String label = "GRADE".equals(d.trait().getDataType()) ? code + " 级" : option.getLabel();
                return new Key(label, option.getSortOrder() == null ? 0 : option.getSortOrder());
            }
        }
        return new Key("GRADE".equals(d.trait().getDataType()) ? code + " 级" : code, code);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable a, Comparable b) {
        if (a instanceof String sa && b instanceof String sb) {
            return CHINESE.compare(sa, sb);
        }
        if (a.getClass() == b.getClass()) {
            return a.compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    private static BigDecimal value(Rec r, TraitDef trait) {
        return ComputedTraits.compute(trait, r.latest(), r.plot());
    }

    private static BigDecimal aggregate(List<Rec> recs, Measure m) {
        if (m.trait() == null) {
            return BigDecimal.valueOf(recs.size());
        }
        if (m.agg() == Agg.COUNT) {
            // 数值类数能算出值的小区；选项、文字类数记过的小区
            boolean numeric = ComputedTraits.isNumeric(m.trait());
            long n = recs.stream().filter(r -> numeric ? value(r, m.trait()) != null
                    : r.latest().containsKey(m.trait().getCode())).count();
            return BigDecimal.valueOf(n);
        }
        List<BigDecimal> values = recs.stream().map(r -> value(r, m.trait())).filter(v -> v != null).toList();
        return Aggregates.of(m.agg(), values, scale(m.trait()));
    }

    private static int scale(TraitDef trait) {
        return trait.getPrecisionDigits() == null ? 2 : trait.getPrecisionDigits();
    }

    private static List<Column> measureColumns(List<Measure> measures) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < measures.size(); i++) {
            Measure m = measures.get(i);
            if (m.trait() == null) {
                columns.add(new Column("m" + i, "小区数", "个", "MEASURE"));
                continue;
            }
            String unit = switch (m.agg()) {
                case COUNT -> "个";
                case CV -> "%";
                default -> "级".equals(m.trait().getUnit()) ? "级" : m.trait().getUnit();
            };
            columns.add(new Column("m" + i, m.trait().getName() + " · " + AGG_NAMES.get(m.agg()), unit, "MEASURE"));
        }
        return columns;
    }

    private static String plotLabel(Plot plot) {
        return plot.getCode() == null ? plot.getRowNo() + "-" + plot.getColNo() : plot.getCode();
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static String replicateLabel(Integer rep) {
        if (rep == null) {
            return "未设重复";
        }
        return "重复 " + (rep >= 1 && rep <= ROMAN.length ? ROMAN[rep - 1] : String.valueOf(rep));
    }

    private static ApiException tooMany() {
        return ApiException.badRequest("结果超过 " + MAX_ROWS + " 行，请缩小范围或换个分组方式");
    }

    /** 汇总一组数值。没有数值时返回 null */
    static final class Aggregates {

        private Aggregates() {
        }

        static BigDecimal of(Agg agg, List<BigDecimal> values, int scale) {
            if (agg == Agg.COUNT) {
                return BigDecimal.valueOf(values.size());
            }
            if (values.isEmpty()) {
                return null;
            }
            return switch (agg) {
                case SUM -> sum(values).setScale(scale, RoundingMode.HALF_UP);
                case AVG -> mean(values).setScale(scale, RoundingMode.HALF_UP);
                case MAX -> values.stream().max(BigDecimal::compareTo).orElseThrow().setScale(scale, RoundingMode.HALF_UP);
                case MIN -> values.stream().min(BigDecimal::compareTo).orElseThrow().setScale(scale, RoundingMode.HALF_UP);
                case MEDIAN -> median(values).setScale(scale, RoundingMode.HALF_UP);
                case CV -> cv(values);
                case COUNT -> throw new IllegalStateException();
            };
        }

        private static BigDecimal sum(List<BigDecimal> values) {
            return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private static BigDecimal mean(List<BigDecimal> values) {
            return sum(values).divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);
        }

        private static BigDecimal median(List<BigDecimal> values) {
            List<BigDecimal> sorted = values.stream().sorted().toList();
            int n = sorted.size();
            return n % 2 == 1 ? sorted.get(n / 2)
                    : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, MathContext.DECIMAL64);
        }

        /** 样本标准差 ÷ 平均值 × 100，保留 1 位小数；少于 2 个值或平均值为 0 时没有意义，返回 null */
        private static BigDecimal cv(List<BigDecimal> values) {
            if (values.size() < 2) {
                return null;
            }
            BigDecimal mean = mean(values);
            if (mean.signum() == 0) {
                return null;
            }
            BigDecimal squares = values.stream().map(v -> v.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sd = squares.divide(BigDecimal.valueOf(values.size() - 1L), MathContext.DECIMAL64).sqrt(MathContext.DECIMAL64);
            return sd.multiply(BigDecimal.valueOf(100)).divide(mean.abs(), 1, RoundingMode.HALF_UP);
        }
    }

    /** 给图表、导出用：结果的列名（带单位） */
    static List<String> headers(AnalysisResult result) {
        return result.columns().stream()
                .map(c -> c.unit() == null || c.unit().isBlank() ? c.label() : c.label() + "（" + c.unit() + "）")
                .collect(Collectors.toList());
    }
}
