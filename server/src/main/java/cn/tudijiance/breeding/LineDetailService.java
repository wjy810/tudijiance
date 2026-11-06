package cn.tudijiance.breeding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.breeding.BreedingDtos.LineDetail;
import cn.tudijiance.breeding.BreedingDtos.LineRow;
import cn.tudijiance.breeding.BreedingDtos.ParentPlace;
import cn.tudijiance.breeding.BreedingDtos.PlantValueItem;
import cn.tudijiance.breeding.BreedingDtos.PlantValueView;
import cn.tudijiance.breeding.BreedingDtos.PlantValuesRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantingRow;
import cn.tudijiance.breeding.BreedingDtos.PlotValueRow;
import cn.tudijiance.breeding.BreedingDtos.SeasonValues;
import cn.tudijiance.breeding.BreedingDtos.TraitValueRow;
import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ComputedTraits;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.TraitValues;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.observation.TrialContextLoader;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.variety.Variety;

/**
 * 品系详情：从哪来、父母和子代、每季种在哪、选出时的单株数据、各季的数据（每季按位置取平均）。
 */
@Service
public class LineDetailService {

    private final LineService lineService;
    private final BreedingIndex.Loader loader;
    private final FieldService fieldService;
    private final LinePlantValueMapper plantValueMapper;
    private final TraitCatalog catalog;
    private final TrialContextLoader contextLoader;
    private final ComputedTraits computedTraits;
    private final Clock clock;

    public LineDetailService(LineService lineService, BreedingIndex.Loader loader, FieldService fieldService,
                             LinePlantValueMapper plantValueMapper, TraitCatalog catalog,
                             TrialContextLoader contextLoader, ComputedTraits computedTraits, Clock clock) {
        this.lineService = lineService;
        this.loader = loader;
        this.fieldService = fieldService;
        this.plantValueMapper = plantValueMapper;
        this.catalog = catalog;
        this.contextLoader = contextLoader;
        this.computedTraits = computedTraits;
        this.clock = clock;
    }

    public LineDetail detail(Long id) {
        BreedingIndex idx = loader.load();
        Variety v = idx.lines().get(id);
        if (v == null) {
            throw ApiException.notFound("品系");
        }
        Map<Long, LineRow> rows = lineService.rowsById(idx);
        Places places = new Places(fieldService, idx.fields());

        List<LineRow> parents = idx.parentsOf(v).stream().map(p -> rows.get(p.getId())).toList();
        List<LineRow> children = idx.childrenOf(id).stream().map(c -> rows.get(c.getId()))
                .sorted(LineService.ROW_ORDER).toList();
        List<PlantingRow> plantings = idx.plantingsOf(id).stream()
                .sorted(Comparator.comparing((Planting p) -> idx.seasons().get(idx.seasonOf(p)), BreedingIndex.BY_START)
                        .thenComparing(Planting::getPlotId))
                .map(p -> plantingRow(p, idx, places)).toList();
        return new LineDetail(rows.get(id), originText(v, idx, places), parents, children,
                parentPlaces(v, idx, places), plantings, plantValues(id), seasonValues(id, idx, places));
    }

    // ---------- 单株数据 ----------

    public List<PlantValueView> plantValues(Long lineId) {
        Map<Long, TraitDef> traits = catalog.all();
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        return plantValueMapper.selectList(Wrappers.<LinePlantValue>lambdaQuery().eq(LinePlantValue::getLineId, lineId))
                .stream()
                .filter(pv -> traits.containsKey(pv.getTraitId()))
                .sorted(Comparator.comparing(pv -> traits.get(pv.getTraitId()).getSortOrder()))
                .map(pv -> {
                    TraitDef t = traits.get(pv.getTraitId());
                    return new PlantValueView(t.getId(), t.getName(), pv.getValueNum(), pv.getValueText(),
                            pv.getValueDate(), TraitValues.display(t, options.getOrDefault(t.getId(), List.of()),
                                    pv.getValueNum(), pv.getValueText(), pv.getValueDate()));
                })
                .toList();
    }

    /** 选出时的单株数据：整份替换，值都空着的项目表示不记 */
    @Transactional
    public List<PlantValueView> savePlantValues(Long lineId, PlantValuesRequest req, long userId) {
        Variety v = lineService.require(lineId);
        if (!Variety.SELECTION.equals(v.getLineKind())) {
            throw ApiException.badRequest("只有选株后代有选出时的单株数据");
        }
        plantValueMapper.delete(Wrappers.<LinePlantValue>lambdaQuery().eq(LinePlantValue::getLineId, lineId));
        Set<Long> seen = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now(clock);
        for (PlantValueItem item : req.values()) {
            boolean empty = item.valueNum() == null && item.valueDate() == null
                    && (item.valueText() == null || item.valueText().isBlank());
            if (empty || !seen.add(item.traitId())) {
                continue;
            }
            TraitDef t = catalog.get(item.traitId());
            if (!Boolean.TRUE.equals(t.getEnabled()) || Boolean.TRUE.equals(t.getIsComputed())
                    || "OPERATION".equals(t.getDataType())) {
                throw ApiException.badRequest(t.getName() + "不能作为单株数据");
            }
            TraitValues.Value value = TraitValues.validate(t, catalog.optionsOf(t.getId()),
                    item.valueNum(), item.valueText(), item.valueDate());
            LinePlantValue pv = new LinePlantValue();
            pv.setLineId(lineId);
            pv.setTraitId(t.getId());
            pv.setValueNum(value.num());
            pv.setValueText(value.text());
            pv.setValueDate(value.date());
            pv.setRecordedBy(userId);
            pv.setRecordedAt(now);
            plantValueMapper.insert(pv);
        }
        return plantValues(lineId);
    }

    // ---------- 从哪来、种在哪 ----------

    private String originText(Variety v, BreedingIndex idx, Places places) {
        Season s = v.getOriginSeasonId() == null ? null : idx.seasons().get(v.getOriginSeasonId());
        String season = s == null ? "" : s.getName() + " ";
        if (Variety.CROSS.equals(v.getLineKind())) {
            return season + "杂交：" + name(idx, v.getMotherId()) + "（母本）× " + name(idx, v.getFatherId()) + "（父本）";
        }
        if (Variety.SELECTION.equals(v.getLineKind())) {
            StringBuilder sb = new StringBuilder("选自 ").append(season);
            if (v.getOriginFieldId() != null) {
                if (v.getOriginPlotId() != null) {
                    sb.append(places.spot(v.getOriginFieldId(), v.getOriginPlotId()).text());
                } else {
                    Field f = idx.fields().get(v.getOriginFieldId());
                    sb.append(f == null ? "" : f.getName());
                    String block = places.blockName(v.getOriginFieldId(), v.getOriginBlockId());
                    if (block != null) {
                        sb.append(' ').append(block);
                    }
                }
            }
            if (v.getTagNo() != null) {
                sb.append("，挂牌 ").append(v.getTagNo()).append(" 号");
            }
            return sb.toString().trim();
        }
        return "原始材料";
    }

    /** 父母在杂交（选株）那一季种在哪：平面图上一键打开那块地块并高亮 */
    private List<ParentPlace> parentPlaces(Variety v, BreedingIndex idx, Places places) {
        if (v.getOriginSeasonId() == null) {
            return List.of();
        }
        Season season = idx.seasons().get(v.getOriginSeasonId());
        List<ParentPlace> result = new ArrayList<>();
        List<Object[]> parents = new ArrayList<>();
        if (Variety.CROSS.equals(v.getLineKind())) {
            parents.add(new Object[] {v.getMotherId(), "母本"});
            parents.add(new Object[] {v.getFatherId(), "父本"});
        } else if (Variety.SELECTION.equals(v.getLineKind())) {
            parents.add(new Object[] {v.getParentId(), "选自"});
        }
        for (Object[] pr : parents) {
            Long pid = (Long) pr[0];
            Variety p = idx.lines().get(pid);
            if (p == null) {
                continue;
            }
            Map<Long, List<Long>> byField = new LinkedHashMap<>();
            if (Variety.SELECTION.equals(v.getLineKind()) && v.getOriginFieldId() != null) {
                List<Long> ids = v.getOriginPlotId() != null ? List.of(v.getOriginPlotId())
                        : places.plotsInBlock(v.getOriginFieldId(), v.getOriginBlockId()).stream()
                                .filter(plotId -> idx.plantingsOf(pid).stream().anyMatch(pl -> plotId.equals(pl.getPlotId())))
                                .toList();
                byField.put(v.getOriginFieldId(), ids);
            } else {
                idx.plantingsOf(pid).stream().filter(pl -> season.getId().equals(idx.seasonOf(pl)))
                        .forEach(pl -> byField.computeIfAbsent(fieldOf(idx, pl), k -> new ArrayList<>()).add(pl.getPlotId()));
            }
            byField.forEach((fieldId, plotIds) -> {
                Field f = idx.fields().get(fieldId);
                result.add(new ParentPlace(pid, p.getName(), (String) pr[1], season.getId(), season.getName(), fieldId,
                        f == null ? null : f.getName(), plotIds, places.describe(fieldId, plotIds)));
            });
        }
        return result;
    }

    private PlantingRow plantingRow(Planting p, BreedingIndex idx, Places places) {
        Long seasonId = idx.seasonOf(p);
        Season s = idx.seasons().get(seasonId);
        Long fieldId = fieldOf(idx, p);
        Places.Spot spot = places.spot(fieldId, p.getPlotId());
        return new PlantingRow(seasonId, s == null ? null : s.getName(), fieldId, spot.fieldName(), p.getTrialId(),
                p.getPlotId(), spot.plotCode(), spot.blockId(), spot.blockName(), spot.text(), p.getGeneration());
    }

    private static Long fieldOf(BreedingIndex idx, Planting p) {
        return idx.trialByField().entrySet().stream().filter(e -> e.getValue().getId().equals(p.getTrialId()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static String name(BreedingIndex idx, Long id) {
        Variety v = id == null ? null : idx.lines().get(id);
        return v == null ? "？" : v.getName();
    }

    // ---------- 各季的数据 ----------

    private record Point(Long plotId, Places.Spot spot, BigDecimal num, LocalDate date, String display) {
    }

    /** 每季每个项目：各位置最近一次的值，数值和日期取平均，其它列出不同的值 */
    private List<SeasonValues> seasonValues(Long lineId, BreedingIndex idx, Places places) {
        List<Planting> mine = idx.plantingsOf(lineId);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<TraitDef> traits = catalog.all().values().stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()) && !"OPERATION".equals(t.getDataType())
                        && (!Boolean.TRUE.equals(t.getIsComputed()) || ComputedTraits.isSupported(t)))
                .sorted(Comparator.comparing(TraitDef::getSortOrder))
                .toList();
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        Map<Long, List<Planting>> bySeason = mine.stream().collect(Collectors.groupingBy(idx::seasonOf));
        List<Season> seasons = bySeason.keySet().stream().map(idx.seasons()::get).filter(Objects::nonNull)
                .sorted(BreedingIndex.BY_START).toList();

        List<SeasonValues> result = new ArrayList<>();
        for (Season s : seasons) {
            Map<Long, List<Point>> points = new LinkedHashMap<>();
            Map<Long, List<Planting>> byTrial = bySeason.get(s.getId()).stream()
                    .collect(Collectors.groupingBy(Planting::getTrialId, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<Planting>> e : byTrial.entrySet()) {
                TrialContext ctx = contextLoader.load(e.getKey());
                List<Long> plantingIds = e.getValue().stream().map(Planting::getId).toList();
                Map<Long, Map<String, Observation>> latest = computedTraits.latest(plantingIds, traits);
                for (Planting pl : e.getValue()) {
                    Plot plot = ctx.plots().get(pl.getPlotId());
                    Map<String, Observation> byCode = latest.getOrDefault(pl.getId(), Map.of());
                    Places.Spot spot = places.spot(plot.getFieldId(), plot.getId());
                    for (TraitDef t : traits) {
                        Point point = point(t, byCode, plot, spot, options.getOrDefault(t.getId(), List.of()));
                        if (point != null) {
                            points.computeIfAbsent(t.getId(), k -> new ArrayList<>()).add(point);
                        }
                    }
                }
            }
            List<TraitValueRow> rows = new ArrayList<>();
            for (TraitDef t : traits) {
                List<Point> list = points.get(t.getId());
                if (list != null) {
                    rows.add(summarize(t, list, options.getOrDefault(t.getId(), List.of())));
                }
            }
            Integer generation = bySeason.get(s.getId()).stream().map(Planting::getGeneration)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            result.add(new SeasonValues(s.getId(), s.getName(), generation, rows));
        }
        return result;
    }

    private static Point point(TraitDef t, Map<String, Observation> byCode, Plot plot, Places.Spot place,
                               List<TraitOption> options) {
        if (Boolean.TRUE.equals(t.getIsComputed())) {
            BigDecimal v = ComputedTraits.compute(t, byCode, plot);
            return v == null ? null : new Point(plot.getId(), place, v, null,
                    TraitValues.display(t, List.of(), round(t, v), null, null));
        }
        Observation o = byCode.get(t.getCode());
        if (o == null) {
            return null;
        }
        return new Point(plot.getId(), place, ComputedTraits.isNumeric(t) ? o.getValueNum() : null, o.getValueDate(),
                TraitValues.display(t, options, o.getValueNum(), o.getValueText(), o.getValueDate()));
    }

    private static TraitValueRow summarize(TraitDef t, List<Point> list, List<TraitOption> options) {
        BigDecimal average = null;
        String display;
        List<BigDecimal> nums = list.stream().map(Point::num).filter(Objects::nonNull).toList();
        List<LocalDate> dates = list.stream().map(Point::date).filter(Objects::nonNull).toList();
        if (ComputedTraits.isNumeric(t) && !nums.isEmpty()) {
            BigDecimal sum = nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            int scale = t.getPrecisionDigits() == null ? 2 : t.getPrecisionDigits() + ("GRADE".equals(t.getDataType()) ? 1 : 0);
            average = sum.divide(BigDecimal.valueOf(nums.size()), scale, RoundingMode.HALF_UP);
            if (nums.size() == 1) {
                display = list.get(0).display();
            } else if ("GRADE".equals(t.getDataType())) {
                display = average.stripTrailingZeros().toPlainString() + " 级（平均）";
            } else {
                display = TraitValues.display(t, List.of(), average, null, null);
            }
        } else if ("DATE".equals(t.getDataType()) && !dates.isEmpty()) {
            long mean = Math.round(dates.stream().mapToLong(LocalDate::toEpochDay).average().orElse(0));
            display = LocalDate.ofEpochDay(mean).toString();
        } else {
            display = list.stream().map(Point::display).distinct().collect(Collectors.joining("、"));
        }
        String type = Boolean.TRUE.equals(t.getIsComputed()) ? "NUMBER" : t.getDataType();
        return new TraitValueRow(t.getId(), t.getName(), t.getCategory(), t.getSortOrder(),
                Boolean.TRUE.equals(t.getImportant()), average, display, list.size(),
                list.stream().map(p -> new PlotValueRow(p.plotId(), p.spot().text(), p.display(), p.num(),
                        group(p.spot()), p.spot().plotCode())).toList(),
                type, t.getUnit());
    }

    /** 哪块地块的哪个区块 */
    private static String group(Places.Spot s) {
        return ((s.fieldName() == null ? "" : s.fieldName()) + " " + (s.blockName() == null ? "" : s.blockName())).trim();
    }

    private static BigDecimal round(TraitDef t, BigDecimal v) {
        return t.getPrecisionDigits() == null ? v : v.setScale(t.getPrecisionDigits(), RoundingMode.HALF_UP);
    }
}
