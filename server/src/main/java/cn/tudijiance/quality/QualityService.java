package cn.tudijiance.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ComputedTraits;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.stats.StatsService;
import cn.tudijiance.stats.StatsService.VarietyRow;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.TrialBookService;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserMapper;
import cn.tudijiance.variety.Variety;

/**
 * 数据质量检查（标准第 9 章能自动判断的几条，加上录入时容易出的错）：
 * <ul>
 *   <li>布局：试验小区没分配品种、没设重复，没有对照品种，各品种重复数不一样；</li>
 *   <li>漏录：重要项目已经开始录了，还有小区没录（2 个及以上小区缺数据可以申请报废）；</li>
 *   <li>超出常见范围、和其它小区差得很多（离群）；</li>
 *   <li>日期：生育期先后不对、日期还没到、不在这一季；同一个生育期录了两个不同的日期；</li>
 *   <li>产量：试验误差变异系数 ≥ 15%、某品种重复间差异大、比对照增产 ≥ 30%；</li>
 *   <li>缺苗：基本苗比记载本里的密度少 20% 以上的小区；</li>
 *   <li>标了异常的小区。</li>
 * </ul>
 * 每个问题有一个稳定的 key：老师核实过（确认没错）后记下来，以后不再算进待处理。
 */
@Service
public class QualityService {

    public static final String ERROR = "ERROR";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final double CV_LIMIT = 15;
    private static final double GAIN_LIMIT = 30;
    private static final double SEEDLING_LOSS = 0.20;
    /** 生育期的先后顺序 */
    private static final List<String> PHENOLOGY_ORDER = List.of(
            "sowing_date", "emergence_date", "heading_date", "flowering_date", "maturity_date");

    private final TrialContextLoader contextLoader;
    private final ObservationMapper observationMapper;
    private final TraitCatalog catalog;
    private final StatsService statsService;
    private final TrialBookService bookService;
    private final QualityAckMapper ackMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public QualityService(TrialContextLoader contextLoader, ObservationMapper observationMapper, TraitCatalog catalog,
                          StatsService statsService, TrialBookService bookService, QualityAckMapper ackMapper,
                          UserMapper userMapper, Clock clock) {
        this.contextLoader = contextLoader;
        this.observationMapper = observationMapper;
        this.catalog = catalog;
        this.statsService = statsService;
        this.bookService = bookService;
        this.ackMapper = ackMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    /**
     * 一个问题。plotIds 是涉及的小区，observationIds 是涉及的那条记录（可以去改、申请更正）。
     * acked：老师核实过，确认没错。
     */
    public record Issue(String key, String level, String kind, String title, String detail, Long traitId,
                        String traitName, List<Long> plotIds, List<String> plotCodes, List<Long> observationIds,
                        boolean acked, String ackNote, String ackedBy, LocalDateTime ackedAt) {

        Issue withAck(QualityAck a, String by) {
            return new Issue(key, level, kind, title, detail, traitId, traitName, plotIds, plotCodes, observationIds,
                    true, a.getNote(), by, a.getAckedAt());
        }
    }

    /** 一个重要项目录了多少个小区；started：这个试验里已经开始录了 */
    public record Completeness(Long traitId, String traitName, String category, int recorded, int total,
                               boolean started) {
    }

    /** 待处理的问题数（不含核实过的）、核实过的数、已种小区数、产量误差变异系数、重要项目完整度（%） */
    public record Summary(int errors, int warnings, int infos, int acked, int planted, BigDecimal yieldCv,
                          Integer completenessPercent) {
    }

    public record QualityReport(Long trialId, String trialName, Integer seasonYear, LocalDateTime checkedAt,
                                Summary summary, List<Issue> issues, List<Completeness> completeness) {
    }

    /** 检查时用到的东西：种了品种的试验小区、每个小区每个项目的记录（最近的在前） */
    private record Ctx(TrialContext trial, Map<Long, TraitDef> traits, Map<String, TraitDef> byCode,
                       List<Planting> planted, Map<Long, Map<Long, List<Observation>>> obs) {

        Plot plot(Planting p) {
            return trial.plots().get(p.getPlotId());
        }

        String code(Planting p) {
            Plot plot = plot(p);
            return plot.getCode() == null ? plot.getRowNo() + "-" + plot.getColNo() : plot.getCode();
        }

        List<Observation> of(Planting p, TraitDef t) {
            return obs.getOrDefault(p.getId(), Map.of()).getOrDefault(t.getId(), List.of());
        }

        Observation latest(Planting p, TraitDef t) {
            List<Observation> list = of(p, t);
            return list.isEmpty() ? null : list.getFirst();
        }

        Map<String, Observation> latestByCode(Planting p) {
            Map<String, Observation> out = new HashMap<>();
            obs.getOrDefault(p.getId(), Map.of()).forEach((traitId, list) -> {
                TraitDef t = traits.get(traitId);
                if (t != null && !list.isEmpty()) {
                    out.put(t.getCode(), list.getFirst());
                }
            });
            return out;
        }
    }

    public QualityReport check(Long trialId) {
        TrialContext trial = contextLoader.load(trialId);
        Map<Long, TraitDef> traits = catalog.all();
        Map<String, TraitDef> byCode = traits.values().stream().collect(Collectors.toMap(TraitDef::getCode, t -> t));
        List<Planting> planted = trial.plantingsById().values().stream()
                .filter(p -> p.getVarietyId() != null)
                .filter(p -> {
                    Plot plot = trial.plots().get(p.getPlotId());
                    return plot != null && Plot.KIND_PLOT.equals(plot.getKind());
                })
                .sorted(Comparator.comparing((Planting p) -> trial.plots().get(p.getPlotId()).getRowNo())
                        .thenComparing(p -> trial.plots().get(p.getPlotId()).getColNo()))
                .toList();
        Map<Long, Map<Long, List<Observation>>> obs = new HashMap<>();
        if (!trial.plantingsById().isEmpty()) {
            observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                            .in(Observation::getPlantingId, trial.plantingsById().keySet())
                            .eq(Observation::getDeleted, false)
                            .orderByDesc(Observation::getObservedOn).orderByDesc(Observation::getId))
                    .forEach(o -> obs.computeIfAbsent(o.getPlantingId(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(o.getTraitId(), k -> new ArrayList<>()).add(o));
        }
        Ctx c = new Ctx(trial, traits, byCode, planted, obs);

        List<Issue> issues = new ArrayList<>();
        layout(c, issues);
        List<Completeness> completeness = missing(c, issues);
        Set<String> outOfRange = new java.util.HashSet<>();
        range(c, issues, outOfRange);
        outliers(c, issues, outOfRange);
        dates(c, issues);
        conflicts(c, issues);
        BigDecimal cv = yieldChecks(c, issues);
        seedlings(c, issues);
        abnormal(c, issues);

        // 核实过的
        Map<String, QualityAck> acks = ackMapper.selectList(Wrappers.<QualityAck>lambdaQuery()
                        .eq(QualityAck::getTrialId, trialId))
                .stream().collect(Collectors.toMap(QualityAck::getIssueKey, Function.identity(), (a, b) -> a));
        Map<Long, String> names = new HashMap<>();
        List<Issue> result = issues.stream().map(i -> {
            QualityAck a = acks.get(i.key());
            if (a == null) {
                return i;
            }
            String by = names.computeIfAbsent(a.getAckedBy(), id -> {
                User u = userMapper.selectById(id);
                return u == null ? null : u.getDisplayName() == null ? u.getUsername() : u.getDisplayName();
            });
            return i.withAck(a, by);
        }).sorted(Comparator.comparing(Issue::acked)
                .thenComparing(i -> List.of(ERROR, WARN, INFO).indexOf(i.level()))
                .thenComparing(Issue::kind)).toList();

        int errors = (int) result.stream().filter(i -> !i.acked() && ERROR.equals(i.level())).count();
        int warnings = (int) result.stream().filter(i -> !i.acked() && WARN.equals(i.level())).count();
        int infos = (int) result.stream().filter(i -> !i.acked() && INFO.equals(i.level())).count();
        int acked = (int) result.stream().filter(Issue::acked).count();
        int cells = completeness.stream().filter(Completeness::started).mapToInt(Completeness::total).sum();
        int filled = completeness.stream().filter(Completeness::started).mapToInt(Completeness::recorded).sum();
        Integer percent = cells == 0 ? null : (int) Math.floor(filled * 100.0 / cells);
        return new QualityReport(trialId, trial.trial().getName(), trial.trial().getSeasonYear(),
                LocalDateTime.now(clock), new Summary(errors, warnings, infos, acked, planted.size(), cv, percent),
                result, completeness);
    }

    // ---------- 布局 ----------

    private void layout(Ctx c, List<Issue> issues) {
        List<Planting> plotRows = c.trial().plantingsById().values().stream()
                .filter(p -> {
                    Plot plot = c.trial().plots().get(p.getPlotId());
                    return plot != null && Plot.KIND_PLOT.equals(plot.getKind());
                }).toList();
        long trialPlots = c.trial().plots().values().stream().filter(p -> Plot.KIND_PLOT.equals(p.getKind())).count();
        long noVariety = trialPlots - c.planted().size();
        if (c.planted().isEmpty()) {
            issues.add(issue("layout:no-variety", INFO, "LAYOUT", "还没分配品种",
                    "在平面图的“布置试验”里给小区分配品种和重复后，才能检查数据", null, List.of(), c, List.of()));
            return;
        }
        if (noVariety > 0) {
            List<Planting> missing = plotRows.stream().filter(p -> p.getVarietyId() == null).toList();
            issues.add(issue("layout:no-variety:" + noVariety, WARN, "LAYOUT", noVariety + " 个试验小区没分配品种",
                    "没分配品种的小区不参加统计。如果是空着的，可以在“布置试验”里改成保护区或走道", null, missing, c, List.of()));
        }
        boolean someRep = c.planted().stream().anyMatch(p -> p.getReplicateNo() != null);
        List<Planting> noRep = c.planted().stream().filter(p -> p.getReplicateNo() == null).toList();
        if (someRep && !noRep.isEmpty()) {
            issues.add(issue("layout:no-rep:" + noRep.size(), WARN, "LAYOUT", noRep.size() + " 个小区没设重复",
                    "没设重复的小区算不了试验误差变异系数，记载本导出时也分不到各重复", null, noRep, c, List.of()));
        }
        boolean hasCk = c.planted().stream().map(p -> c.trial().varietyOf(p))
                .anyMatch(v -> v != null && v.getControlType() != null);
        if (!hasCk) {
            issues.add(issue("layout:no-ck", WARN, "LAYOUT", "没有对照品种",
                    "种的品种里没有标为对照的，比对照增减产、位次比较算不出来。在“品种”里把对照品种标出来", null, List.of(), c,
                    List.of()));
        }
        if (someRep) {
            Map<Long, Set<Integer>> repsByVariety = new HashMap<>();
            c.planted().forEach(p -> {
                if (p.getReplicateNo() != null) {
                    repsByVariety.computeIfAbsent(p.getVarietyId(), k -> new TreeSet<>()).add(p.getReplicateNo());
                }
            });
            int maxReps = repsByVariety.values().stream().mapToInt(Set::size).max().orElse(0);
            List<String> uneven = repsByVariety.entrySet().stream().filter(e -> e.getValue().size() < maxReps)
                    .map(e -> c.trial().varieties().get(e.getKey()).getName() + "（" + e.getValue().size() + " 个）")
                    .sorted().toList();
            if (!uneven.isEmpty()) {
                issues.add(issue("layout:uneven:" + String.join(",", uneven), INFO, "LAYOUT",
                        uneven.size() + " 个品种的重复不够 " + maxReps + " 个",
                        String.join("、", uneven) + "。重复不齐的品种不参加试验误差变异系数的计算", null, List.of(), c, List.of()));
            }
        }
    }

    // ---------- 漏录 ----------

    private List<Completeness> missing(Ctx c, List<Issue> issues) {
        List<Completeness> out = new ArrayList<>();
        int total = c.planted().size();
        if (total == 0) {
            return out;
        }
        List<TraitDef> important = c.traits().values().stream()
                .filter(t -> Boolean.TRUE.equals(t.getImportant()) && Boolean.TRUE.equals(t.getEnabled())
                        && !Boolean.TRUE.equals(t.getIsComputed()) && !"OPERATION".equals(t.getCategory()))
                .sorted(Comparator.comparing(TraitDef::getSortOrder)).toList();
        for (TraitDef t : important) {
            List<Planting> lacking = c.planted().stream().filter(p -> c.of(p, t).isEmpty()).toList();
            int recorded = total - lacking.size();
            boolean started = recorded > 0;
            out.add(new Completeness(t.getId(), t.getName(), t.getCategory(), recorded, total, started));
            if (!started || lacking.isEmpty()) {
                continue;
            }
            String level = lacking.size() >= 2 ? ("plot_yield".equals(t.getCode()) ? ERROR : WARN) : INFO;
            String detail = lacking.size() >= 2
                    ? "已经录了 " + recorded + " 个小区，还有 " + lacking.size() + " 个没录。标准第 9 章：2 个及以上小区数据缺失可以申请报废"
                    : "已经录了 " + recorded + " 个小区，还差 1 个";
            issues.add(issue("missing:" + t.getCode() + ":" + lacking.size(), level, "MISSING",
                    t.getName() + "：" + lacking.size() + " 个小区没录", detail, t, lacking, c, List.of()));
        }
        return out;
    }

    // ---------- 超出常见范围、离群 ----------

    private void range(Ctx c, List<Issue> issues, Set<String> flagged) {
        for (TraitDef t : c.traits().values()) {
            if (t.getMinValue() == null && t.getMaxValue() == null) {
                continue;
            }
            boolean computed = Boolean.TRUE.equals(t.getIsComputed());
            List<Issue> perPlot = new ArrayList<>();
            List<String> samples = new ArrayList<>();
            for (Planting p : c.planted()) {
                BigDecimal v;
                Long obsId = null;
                if (computed) {
                    if (!ComputedTraits.isSupported(t)) {
                        continue;
                    }
                    v = ComputedTraits.compute(t, c.latestByCode(p), c.plot(p));
                } else {
                    Observation o = c.latest(p, t);
                    v = o == null ? null : o.getValueNum();
                    obsId = o == null ? null : o.getId();
                }
                if (v == null) {
                    continue;
                }
                boolean low = t.getMinValue() != null && v.compareTo(t.getMinValue()) < 0;
                boolean high = t.getMaxValue() != null && v.compareTo(t.getMaxValue()) > 0;
                if (!low && !high) {
                    continue;
                }
                flagged.add(t.getCode() + ":" + p.getId());
                String shown = plain(v) + unit(t);
                samples.add(c.code(p) + " 是 " + shown);
                String detail = "常见范围 " + rangeText(t) + (computed ? "。这是算出来的，看看用到的数据（" + sourcesText(c, t) + "）有没有录错" : "，确认没录错？");
                perPlot.add(issue("range:" + t.getCode() + ":" + p.getId() + ":" + plain(v), WARN, "RANGE",
                        c.code(p) + " 的" + t.getName() + " " + shown + (low ? " 偏低" : " 偏高"), detail, t, List.of(p), c,
                        obsId == null ? List.of() : List.of(obsId)));
            }
            if (perPlot.size() <= MAX_PER_PLOT_RANGE) {
                issues.addAll(perPlot);
                continue;
            }
            // 很多小区一起超出，多半是单位或录入方式弄错了，合成一条
            List<Long> plotIds = perPlot.stream().flatMap(i -> i.plotIds().stream()).toList();
            List<String> codes = perPlot.stream().flatMap(i -> i.plotCodes().stream()).toList();
            String sample = String.join("，", samples.subList(0, 4));
            issues.add(new Issue(compactKey("range:" + t.getCode() + ":" + perPlot.stream().map(Issue::key).collect(Collectors.joining(";"))),
                    WARN, "RANGE", perPlot.size() + " 个小区的" + t.getName() + "超出常见范围",
                    "常见范围 " + rangeText(t) + "。" + sample + (perPlot.size() > 4 ? " 等" : "")
                            + "。这么多一起超出，看看是不是单位弄错了（例如 m 和 cm）", t.getId(), t.getName(), plotIds, codes,
                    perPlot.stream().flatMap(i -> i.observationIds().stream()).toList(), false, null, null, null));
        }
    }

    /** 一个项目超出常见范围的小区不多时逐个列出，方便逐个核实 */
    private static final int MAX_PER_PLOT_RANGE = 3;

    /** 稳健的离群判断：和中位数的差除以中位绝对偏差，超过 3.5 倍 */
    private void outliers(Ctx c, List<Issue> issues, Set<String> flagged) {
        for (TraitDef t : c.traits().values()) {
            if (!"NUMBER".equals(t.getDataType()) || Boolean.TRUE.equals(t.getIsComputed())) {
                continue;
            }
            Map<Planting, Observation> values = new LinkedHashMap<>();
            for (Planting p : c.planted()) {
                Observation o = c.latest(p, t);
                if (o != null && o.getValueNum() != null) {
                    values.put(p, o);
                }
            }
            if (values.size() < 6) {
                continue;
            }
            double[] xs = values.values().stream().mapToDouble(o -> o.getValueNum().doubleValue()).sorted().toArray();
            double med = median(xs);
            double[] dev = values.values().stream().mapToDouble(o -> Math.abs(o.getValueNum().doubleValue() - med))
                    .sorted().toArray();
            double mad = median(dev);
            if (mad <= 1e-9) {
                continue;
            }
            values.forEach((p, o) -> {
                double z = 0.6745 * (o.getValueNum().doubleValue() - med) / mad;
                if (Math.abs(z) > 3.5 && !flagged.contains(t.getCode() + ":" + p.getId())) {
                    issues.add(issue("outlier:" + t.getCode() + ":" + p.getId() + ":" + plain(o.getValueNum()), INFO,
                            "OUTLIER", c.code(p) + " 的" + t.getName() + " " + plain(o.getValueNum()) + unit(t)
                                    + " 和其它小区差得多",
                            "这个试验里一般在 " + plain(BigDecimal.valueOf(med).setScale(1, RoundingMode.HALF_UP)) + unit(t)
                                    + " 左右，确认没录错？", t, List.of(p), c, List.of(o.getId())));
                }
            });
        }
    }

    // ---------- 日期 ----------

    private void dates(Ctx c, List<Issue> issues) {
        int year = c.trial().trial().getSeasonYear();
        LocalDate seasonStart = LocalDate.of(year - 1, 8, 1);
        LocalDate seasonEnd = LocalDate.of(year, 7, 31);
        LocalDate today = LocalDate.now(clock);
        for (Planting p : c.planted()) {
            // 生育期先后
            TraitDef prev = null;
            Observation prevObs = null;
            for (String code : PHENOLOGY_ORDER) {
                TraitDef t = c.byCode().get(code);
                Observation o = t == null ? null : c.latest(p, t);
                if (o == null || o.getValueDate() == null) {
                    continue;
                }
                if (prevObs != null && o.getValueDate().isBefore(prevObs.getValueDate())) {
                    issues.add(issue("order:" + p.getId() + ":" + prev.getCode() + ":" + code + ":" + o.getValueDate(), ERROR,
                            "DATE_ORDER", c.code(p) + " 的" + t.getName() + "早于" + prev.getName(),
                            t.getName() + " " + o.getValueDate() + "，" + prev.getName() + " " + prevObs.getValueDate()
                                    + "。生育期应该是 播种 → 出苗 → 抽穗 → 扬花 → 成熟", t, List.of(p), c,
                            List.of(prevObs.getId(), o.getId())));
                }
                prev = t;
                prevObs = o;
            }
            TraitDef first = c.byCode().get("lodging_first_date");
            TraitDef last = c.byCode().get("lodging_final_date");
            Observation a = first == null ? null : c.latest(p, first);
            Observation b = last == null ? null : c.latest(p, last);
            if (a != null && b != null && a.getValueDate() != null && b.getValueDate() != null
                    && b.getValueDate().isBefore(a.getValueDate())) {
                issues.add(issue("order:" + p.getId() + ":lodging:" + b.getValueDate(), WARN, "DATE_ORDER",
                        c.code(p) + " 的最终倒伏日期早于最初倒伏日期", "最初 " + a.getValueDate() + "，最终 " + b.getValueDate(),
                        last, List.of(p), c, List.of(a.getId(), b.getId())));
            }
            // 日期还没到、不在这一季
            for (Map.Entry<Long, List<Observation>> e : c.obs().getOrDefault(p.getId(), Map.of()).entrySet()) {
                TraitDef t = c.traits().get(e.getKey());
                Observation o = e.getValue().getFirst();
                if (t == null || o.getValueDate() == null) {
                    continue;
                }
                if (o.getValueDate().isAfter(today)) {
                    issues.add(issue("future:" + o.getId() + ":" + o.getValueDate(), ERROR, "DATE_SEASON",
                            c.code(p) + " 的" + t.getName() + " " + o.getValueDate() + " 还没到", "日期是以后的，可能录错了年份或月份",
                            t, List.of(p), c, List.of(o.getId())));
                } else if (o.getValueDate().isBefore(seasonStart) || o.getValueDate().isAfter(seasonEnd)) {
                    issues.add(issue("season:" + o.getId() + ":" + o.getValueDate(), WARN, "DATE_SEASON",
                            c.code(p) + " 的" + t.getName() + " " + o.getValueDate() + " 不在这一季",
                            "这个试验是 " + (year - 1) + " 年秋播、" + year + " 年收获，日期一般在 " + seasonStart + " 到 " + seasonEnd + " 之间",
                            t, List.of(p), c, List.of(o.getId())));
                }
            }
        }
    }

    /** 同一个生育期录了两个不同的日期：统计只用最近录的那个 */
    /** 同一个小区的同一个日期项目录了几个不同的日期。整块田重复录了一遍时合成一条，不按小区刷屏 */
    private void conflicts(Ctx c, List<Issue> issues) {
        for (String code : PHENOLOGY_ORDER) {
            TraitDef t = c.byCode().get(code);
            if (t == null) {
                continue;
            }
            List<Planting> plots = new ArrayList<>();
            List<String> parts = new ArrayList<>();
            List<Long> obsIds = new ArrayList<>();
            String lastUsed = null;
            for (Planting p : c.planted()) {
                List<Observation> list = c.of(p, t).stream().filter(o -> o.getValueDate() != null).toList();
                Set<LocalDate> distinct = list.stream().map(Observation::getValueDate).collect(Collectors.toCollection(TreeSet::new));
                if (distinct.size() < 2) {
                    continue;
                }
                plots.add(p);
                parts.add(c.code(p) + "：" + distinct.stream().map(LocalDate::toString).collect(Collectors.joining("、")));
                obsIds.addAll(list.stream().map(Observation::getId).toList());
                lastUsed = list.getFirst().getValueDate().toString();
            }
            if (plots.isEmpty()) {
                continue;
            }
            String key = "conflict:" + code + ":" + String.join(";", parts);
            if (plots.size() == 1) {
                Planting p = plots.getFirst();
                String dates = parts.getFirst().substring(parts.getFirst().indexOf('：') + 1);
                issues.add(issue(key, WARN, "CONFLICT", c.code(p) + " 的" + t.getName() + "录了 "
                        + (dates.split("、").length) + " 个不同的日期",
                        dates + "。统计用最近录的 " + lastUsed + "，多余的可以删掉", t, plots, c, obsIds));
            } else {
                String sample = String.join("；", parts.subList(0, Math.min(4, parts.size())))
                        + (parts.size() > 4 ? " 等" : "");
                issues.add(issue(key, WARN, "CONFLICT", plots.size() + " 个小区的" + t.getName() + "录了不同的日期",
                        sample + "。可能重复录了一遍，统计用每个小区最近录的那个，多余的可以删掉", t, plots, c, obsIds));
            }
        }
    }

    // ---------- 产量 ----------

    /** 试验误差变异系数（品种 × 重复两向分组，不含交互），以及品种重复间差异、比对照增产 */
    private BigDecimal yieldChecks(Ctx c, List<Issue> issues) {
        TraitDef plotYield = c.byCode().get("plot_yield");
        if (plotYield == null) {
            return null;
        }
        // 品种 → 重复 → 小区产量（同一重复几个小区取平均）
        Map<Long, Map<Integer, List<Double>>> table = new HashMap<>();
        for (Planting p : c.planted()) {
            Observation o = c.latest(p, plotYield);
            if (o == null || o.getValueNum() == null || p.getReplicateNo() == null) {
                continue;
            }
            table.computeIfAbsent(p.getVarietyId(), k -> new HashMap<>())
                    .computeIfAbsent(p.getReplicateNo(), k -> new ArrayList<>()).add(o.getValueNum().doubleValue());
        }
        Set<Integer> reps = new TreeSet<>();
        table.values().forEach(m -> reps.addAll(m.keySet()));
        BigDecimal cv = null;
        List<Long> complete = table.entrySet().stream().filter(e -> e.getValue().keySet().containsAll(reps))
                .map(Map.Entry::getKey).toList();
        int v = complete.size();
        int r = reps.size();
        if (v >= 2 && r >= 2) {
            double[][] y = new double[v][r];
            List<Integer> repList = new ArrayList<>(reps);
            for (int i = 0; i < v; i++) {
                for (int j = 0; j < r; j++) {
                    y[i][j] = table.get(complete.get(i)).get(repList.get(j)).stream().mapToDouble(Double::doubleValue)
                            .average().orElse(0);
                }
            }
            double grand = 0;
            double[] rowMean = new double[v];
            double[] colMean = new double[r];
            for (int i = 0; i < v; i++) {
                for (int j = 0; j < r; j++) {
                    grand += y[i][j];
                    rowMean[i] += y[i][j] / r;
                    colMean[j] += y[i][j] / v;
                }
            }
            grand /= v * r;
            double sse = 0;
            for (int i = 0; i < v; i++) {
                for (int j = 0; j < r; j++) {
                    double e = y[i][j] - rowMean[i] - colMean[j] + grand;
                    sse += e * e;
                }
            }
            double mse = sse / ((v - 1) * (r - 1));
            if (grand > 0) {
                double value = Math.sqrt(mse) / grand * 100;
                cv = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
                if (value >= CV_LIMIT) {
                    issues.add(issue("yield-cv:" + cv, ERROR, "YIELD_CV", "产量误差变异系数 " + cv + "%（≥ 15%）",
                            "按 " + v + " 个品种、" + r + " 个重复的小区产量算。标准第 9 章：≥ 15% 可以申请报废。"
                                    + "先看看重复间差得多的品种（下面单独列出）是不是录错了", plotYield, List.of(), c, List.of()));
                } else if (value >= 10) {
                    issues.add(issue("yield-cv:" + cv, INFO, "YIELD_CV", "产量误差变异系数 " + cv + "%，偏大",
                            "还没到 15%，但已经超过 10%，注意重复间差异大的品种", plotYield, List.of(), c, List.of()));
                }
            }
        }
        // 品种重复间差异
        for (Map.Entry<Long, Map<Integer, List<Double>>> e : table.entrySet()) {
            List<Double> means = e.getValue().values().stream()
                    .map(l -> l.stream().mapToDouble(Double::doubleValue).average().orElse(0)).toList();
            if (means.size() < 2) {
                continue;
            }
            double mean = means.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double sd = Math.sqrt(means.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (means.size() - 1));
            if (mean <= 0 || sd / mean * 100 < CV_LIMIT) {
                continue;
            }
            Variety variety = c.trial().varieties().get(e.getKey());
            List<Planting> plots = c.planted().stream().filter(p -> Objects.equals(p.getVarietyId(), e.getKey())).toList();
            String values = new TreeSet<>(e.getValue().keySet()).stream()
                    .map(rep -> "重复" + rep + " " + plain(BigDecimal.valueOf(e.getValue().get(rep).stream()
                            .mapToDouble(Double::doubleValue).average().orElse(0)).setScale(2, RoundingMode.HALF_UP)))
                    .collect(Collectors.joining("、"));
            BigDecimal pct = BigDecimal.valueOf(sd / mean * 100).setScale(1, RoundingMode.HALF_UP);
            issues.add(issue("variety-cv:" + e.getKey() + ":" + pct, WARN, "VARIETY_CV",
                    variety.getName() + " 各重复的小区产量差得多（变异系数 " + pct + "%）", values + " kg，确认没录错？", plotYield,
                    plots, c, plots.stream().map(p -> c.latest(p, plotYield)).filter(Objects::nonNull).map(Observation::getId).toList()));
        }
        // 比对照增产 ≥ 30%
        TraitDef perMu = c.byCode().get("yield_per_mu");
        if (perMu != null) {
            try {
                for (VarietyRow row : statsService.varietySummary(c.trial().trial().getId(), perMu.getId()).rows()) {
                    if (row.vsCkPercent() != null && row.vsCkPercent().doubleValue() >= GAIN_LIMIT) {
                        List<Planting> plots = c.planted().stream().filter(p -> Objects.equals(p.getVarietyId(), row.varietyId())).toList();
                        issues.add(issue("gain:" + row.varietyId() + ":" + row.vsCkPercent(), WARN, "YIELD_GAIN",
                                row.varietyName() + " 比对照增产 " + plain(row.vsCkPercent()) + "%（≥ 30%）",
                                "标准第 9 章：试验品种增产幅度 ≥ 30% 可以申请报废。请核实小区产量和收获面积", perMu, plots, c, List.of()));
                    }
                }
            } catch (ApiException ignored) {
                // 没有产量数据
            }
        }
        return cv;
    }

    // ---------- 缺苗 ----------

    /** 基本苗比记载本里填的密度少 20% 以上，算缺苗率 ≥ 20% */
    private void seedlings(Ctx c, List<Issue> issues) {
        TraitDef basic = c.byCode().get("basic_seedlings");
        if (basic == null) {
            return;
        }
        BigDecimal density;
        try {
            String d = bookService.get(c.trial().trial().getId()).info().get("density");
            density = d == null || d.isBlank() ? null : new BigDecimal(d.strip());
        } catch (NumberFormatException e) {
            density = null;
        }
        if (density == null || density.signum() <= 0) {
            return;
        }
        double limit = density.doubleValue() * (1 - SEEDLING_LOSS);
        List<Planting> low = c.planted().stream().filter(p -> {
            Observation o = c.latest(p, basic);
            return o != null && o.getValueNum() != null && o.getValueNum().doubleValue() < limit;
        }).toList();
        if (low.isEmpty()) {
            return;
        }
        issues.add(issue("seedling:" + low.size(), low.size() >= 2 ? ERROR : WARN, "SEEDLING_LOSS",
                low.size() + " 个小区缺苗率 ≥ 20%",
                "记载本里密度 " + plain(density) + " 万苗/亩，这些小区基本苗不到 " + plain(BigDecimal.valueOf(limit).setScale(1, RoundingMode.HALF_UP))
                        + " 万/亩。标准第 9 章：2 个及以上小区缺苗率 ≥ 20% 可以申请报废", basic, low, c,
                low.stream().map(p -> c.latest(p, basic).getId()).toList()));
    }

    private void abnormal(Ctx c, List<Issue> issues) {
        List<Planting> list = c.planted().stream().filter(p -> Boolean.TRUE.equals(p.getAbnormal())).toList();
        if (list.isEmpty()) {
            return;
        }
        String notes = list.stream().map(p -> c.code(p) + (p.getAbnormalNote() == null ? "" : "（" + p.getAbnormalNote() + "）"))
                .collect(Collectors.joining("、"));
        issues.add(issue("abnormal:" + list.stream().map(Planting::getId).map(String::valueOf).collect(Collectors.joining(",")),
                INFO, "ABNORMAL", list.size() + " 个小区标了异常",
                notes + "。自然灾害、严重倒伏、病虫害要在 72 小时内书面说明并上传照片", null, list, c, List.of()));
    }

    // ---------- 核实 ----------

    public void ack(Long trialId, String key, String note, long userId) {
        contextLoader.load(trialId);
        if (key == null || key.isBlank() || key.length() > 200) {
            throw ApiException.badRequest("问题编号不对");
        }
        String n = note == null ? null : note.strip();
        if (n != null && n.length() > 200) {
            throw ApiException.badRequest("说明不能超过 200 个字");
        }
        QualityAck existing = ackMapper.selectOne(Wrappers.<QualityAck>lambdaQuery()
                .eq(QualityAck::getTrialId, trialId).eq(QualityAck::getIssueKey, key));
        QualityAck a = existing == null ? new QualityAck() : existing;
        a.setTrialId(trialId);
        a.setIssueKey(key);
        a.setNote(n == null || n.isEmpty() ? null : n);
        a.setAckedBy(userId);
        a.setAckedAt(LocalDateTime.now(clock));
        if (existing == null) {
            ackMapper.insert(a);
        } else {
            ackMapper.updateById(a);
        }
    }

    public void unack(Long trialId, String key) {
        ackMapper.delete(Wrappers.<QualityAck>lambdaQuery().eq(QualityAck::getTrialId, trialId).eq(QualityAck::getIssueKey, key));
    }

    // ---------- 小工具 ----------

    private static Issue issue(String key, String level, String kind, String title, String detail, TraitDef trait,
                               List<Planting> plots, Ctx c, List<Long> observationIds) {
        return new Issue(compactKey(key), level, kind, title, detail, trait == null ? null : trait.getId(),
                trait == null ? null : trait.getName(), plots.stream().map(Planting::getPlotId).toList(),
                plots.stream().map(c::code).toList(), observationIds, false, null, null, null);
    }

    /** 问题的 key 要存进 quality_ack.issue_key（最长 200），涉及很多小区时把后半截换成摘要 */
    static String compactKey(String key) {
        if (key.length() <= 160) {
            return key;
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return key.substring(0, 80) + "#" + java.util.HexFormat.of().formatHex(d, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String unit(TraitDef t) {
        return t.getUnit() == null || "级".equals(t.getUnit()) ? "" : " " + t.getUnit();
    }

    private static String rangeText(TraitDef t) {
        String lo = t.getMinValue() == null ? "" : plain(t.getMinValue());
        String hi = t.getMaxValue() == null ? "" : plain(t.getMaxValue());
        return (t.getMinValue() == null ? "≤ " + hi : t.getMaxValue() == null ? "≥ " + lo : lo + "–" + hi) + unit(t);
    }

    private static String sourcesText(Ctx c, TraitDef t) {
        return ComputedTraits.sourceCodes(t).stream().map(code -> c.byCode().get(code)).filter(Objects::nonNull)
                .map(TraitDef::getName).collect(Collectors.joining("、"));
    }
}
