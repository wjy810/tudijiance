package cn.tudijiance.breeding;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.breeding.BreedingDtos.LandRequest;
import cn.tudijiance.breeding.BreedingDtos.LandSummary;
import cn.tudijiance.breeding.BreedingDtos.SeasonRequest;
import cn.tudijiance.breeding.BreedingDtos.SeasonView;
import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldDtos.FieldDetail;
import cn.tudijiance.field.FieldDtos.FieldRequest;
import cn.tudijiance.field.FieldLayout;
import cn.tudijiance.field.FieldLayout.Block;
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.FieldMapper;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.LayoutGeometry;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialMapper;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

/**
 * 季和地块：新建季时选接着哪一季（或几季），季里新建地块（照着以前的地块划分，或者从空白开始）。
 * 每块地块自带一个育种试验，种植记录和录入的数据都挂在它上面。
 */
@Service
public class SeasonService {

    private final SeasonMapper seasonMapper;
    private final SeasonFollowMapper followMapper;
    private final FieldMapper fieldMapper;
    private final FieldService fieldService;
    private final TrialMapper trialMapper;
    private final TrialService trialService;
    private final PlantingMapper plantingMapper;
    private final ObservationMapper observationMapper;
    private final VarietyMapper varietyMapper;
    private final Clock clock;

    public SeasonService(SeasonMapper seasonMapper, SeasonFollowMapper followMapper, FieldMapper fieldMapper,
                         FieldService fieldService, TrialMapper trialMapper, TrialService trialService,
                         PlantingMapper plantingMapper, ObservationMapper observationMapper,
                         VarietyMapper varietyMapper, Clock clock) {
        this.seasonMapper = seasonMapper;
        this.followMapper = followMapper;
        this.fieldMapper = fieldMapper;
        this.fieldService = fieldService;
        this.trialMapper = trialMapper;
        this.trialService = trialService;
        this.plantingMapper = plantingMapper;
        this.observationMapper = observationMapper;
        this.varietyMapper = varietyMapper;
        this.clock = clock;
    }

    /** 全部季，最新的在前，带接着哪几季和季里的地块 */
    public List<SeasonView> list() {
        List<Season> seasons = seasonMapper.selectList(null).stream()
                .sorted(BreedingIndex.BY_START.reversed()).toList();
        Map<Long, List<Long>> follows = new HashMap<>();
        followMapper.selectList(null).forEach(f ->
                follows.computeIfAbsent(f.getSeasonId(), k -> new ArrayList<>()).add(f.getPrevSeasonId()));
        List<Field> fields = fieldMapper.selectList(Wrappers.<Field>lambdaQuery()
                .isNotNull(Field::getSeasonId).orderByAsc(Field::getId));
        Map<Long, Trial> trialByField = new HashMap<>();
        if (!fields.isEmpty()) {
            trialMapper.selectList(Wrappers.<Trial>lambdaQuery()
                            .in(Trial::getFieldId, fields.stream().map(Field::getId).toList()).orderByAsc(Trial::getId))
                    .forEach(t -> trialByField.putIfAbsent(t.getFieldId(), t));
        }
        Map<Long, List<Planting>> plantingsByTrial = new HashMap<>();
        if (!trialByField.isEmpty()) {
            plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                            .in(Planting::getTrialId, trialByField.values().stream().map(Trial::getId).toList())
                            .isNotNull(Planting::getVarietyId))
                    .forEach(p -> plantingsByTrial.computeIfAbsent(p.getTrialId(), k -> new ArrayList<>()).add(p));
        }
        Map<Long, Long> plotCounts = new HashMap<>();
        if (!fields.isEmpty()) {
            fieldService.plotsOf(fields.stream().map(Field::getId).toList()).stream()
                    .filter(p -> Plot.KIND_PLOT.equals(p.getKind()))
                    .forEach(p -> plotCounts.merge(p.getFieldId(), 1L, Long::sum));
        }
        Map<Long, List<LandSummary>> landsBySeason = new LinkedHashMap<>();
        for (Field f : fields) {
            Trial t = trialByField.get(f.getId());
            List<Planting> ps = t == null ? List.of() : plantingsByTrial.getOrDefault(t.getId(), List.of());
            landsBySeason.computeIfAbsent(f.getSeasonId(), k -> new ArrayList<>()).add(new LandSummary(
                    f.getId(), f.getName(), t == null ? null : t.getId(),
                    plotCounts.getOrDefault(f.getId(), 0L).intValue(), ps.size(),
                    (int) ps.stream().map(Planting::getVarietyId).distinct().count()));
        }
        return seasons.stream()
                .map(s -> new SeasonView(s, follows.getOrDefault(s.getId(), List.of()),
                        landsBySeason.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    public Season get(Long id) {
        Season s = id == null ? null : seasonMapper.selectById(id);
        if (s == null) {
            throw ApiException.notFound("季");
        }
        return s;
    }

    @Transactional
    public SeasonView create(SeasonRequest req) {
        Season s = new Season();
        apply(s, req, null);
        s.setCreatedAt(LocalDateTime.now(clock));
        seasonMapper.insert(s);
        saveFollows(s, req.followIds());
        return view(s.getId());
    }

    @Transactional
    public SeasonView update(Long id, SeasonRequest req) {
        Season s = get(id);
        apply(s, req, id);
        seasonMapper.update(null, Wrappers.<Season>lambdaUpdate()
                .set(Season::getName, s.getName())
                .set(Season::getStartDate, s.getStartDate())
                .set(Season::getEndDate, s.getEndDate())
                .set(Season::getRemark, s.getRemark())
                .eq(Season::getId, id));
        followMapper.delete(Wrappers.<SeasonFollow>lambdaQuery().eq(SeasonFollow::getSeasonId, id));
        saveFollows(s, req.followIds());
        return view(id);
    }

    /** 季里还有地块、或者有品系是在这一季杂交或选出来的，不能删 */
    @Transactional
    public void delete(Long id) {
        Season s = get(id);
        long lands = fieldMapper.selectCount(Wrappers.<Field>lambdaQuery().eq(Field::getSeasonId, id));
        if (lands > 0) {
            throw ApiException.conflict("“" + s.getName() + "”里还有 " + lands + " 块地块，请先删掉地块");
        }
        long lines = varietyMapper.selectCount(Wrappers.<Variety>lambdaQuery().eq(Variety::getOriginSeasonId, id));
        if (lines > 0) {
            throw ApiException.conflict("有 " + lines + " 个品系是在“" + s.getName() + "”杂交或选出来的，不能删除这一季");
        }
        followMapper.delete(Wrappers.<SeasonFollow>lambdaQuery().eq(SeasonFollow::getPrevSeasonId, id));
        seasonMapper.deleteById(id);
    }

    /**
     * 在季里新建地块：照着 copyFromFieldId 的区块划分（只要划分，不带小区块、种植和数据），或者用画好的 layout；
     * 同时建好这块地块的育种试验。
     */
    @Transactional
    public FieldDetail createLand(Long seasonId, LandRequest req) {
        Season season = get(seasonId);
        String name = req.name().trim();
        long same = fieldMapper.selectCount(Wrappers.<Field>lambdaQuery()
                .eq(Field::getSeasonId, seasonId).eq(Field::getName, name));
        if (same > 0) {
            throw ApiException.conflict("“" + season.getName() + "”里已经有叫“" + name + "”的地块");
        }
        Field source = null;
        FieldLayout layout;
        if (req.copyFromFieldId() != null) {
            source = fieldService.get(req.copyFromFieldId());
            FieldLayout saved = fieldService.savedLayout(source);
            layout = withoutPlots(saved != null ? saved
                    : LayoutGeometry.legacy(source, fieldService.plots(source.getId())));
        } else if (req.layout() != null) {
            layout = withoutPlots(req.layout());
        } else {
            throw ApiException.badRequest("请选择照着哪块地块建，或者先画好区块");
        }
        FieldRequest fr = new FieldRequest(name,
                blank(req.location()) ? (source == null ? null : source.getLocation()) : req.location().trim(),
                source == null ? null : source.getLatitude(), source == null ? null : source.getLongitude(),
                source == null ? null : source.getAltitudeM(), null, 1, 1,
                source == null ? null : source.getSoilType(), source == null ? null : source.getIrrigated(),
                null, blank(req.remark()) ? null : req.remark().trim(), null, null, layout);
        FieldDetail detail = fieldService.create(fr);
        Long fieldId = detail.field().getId();
        fieldMapper.update(null, Wrappers.<Field>lambdaUpdate().set(Field::getSeasonId, seasonId).eq(Field::getId, fieldId));
        trialService.createBreeding(fieldId, season.yearOf(), season.getName() + " · " + name);
        return fieldService.detail(fieldId);
    }

    /** 删除地块：录过数据、或者有品系是从这块地块选出来的，不能删 */
    @Transactional
    public void deleteLand(Long seasonId, Long fieldId) {
        Field field = fieldService.get(fieldId);
        if (!seasonId.equals(field.getSeasonId())) {
            throw ApiException.notFound("地块");
        }
        List<Trial> trials = trialMapper.selectList(Wrappers.<Trial>lambdaQuery().eq(Trial::getFieldId, fieldId));
        List<Long> trialIds = trials.stream().map(Trial::getId).toList();
        if (!trialIds.isEmpty()) {
            List<Long> plantingIds = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                    .in(Planting::getTrialId, trialIds)).stream().map(Planting::getId).toList();
            if (!plantingIds.isEmpty() && observationMapper.selectCount(Wrappers.<Observation>lambdaQuery()
                    .in(Observation::getPlantingId, plantingIds).eq(Observation::getDeleted, false)) > 0) {
                throw ApiException.conflict("“" + field.getName() + "”已经录了数据，不能删除");
            }
        }
        long selected = varietyMapper.selectCount(Wrappers.<Variety>lambdaQuery().eq(Variety::getOriginFieldId, fieldId));
        if (selected > 0) {
            throw ApiException.conflict("有 " + selected + " 个品系是从“" + field.getName() + "”选出来的，不能删除");
        }
        trialIds.forEach(trialService::deleteBreeding);
        fieldService.deleteLand(fieldId);
    }

    private SeasonView view(Long id) {
        return list().stream().filter(v -> v.season().getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("季"));
    }

    private void apply(Season s, SeasonRequest req, Long exceptId) {
        String name = req.name().trim();
        var same = Wrappers.<Season>lambdaQuery().eq(Season::getName, name);
        if (exceptId != null) {
            same.ne(Season::getId, exceptId);
        }
        if (seasonMapper.selectCount(same) > 0) {
            throw ApiException.conflict("已经有叫“" + name + "”的季");
        }
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("结束日期不能早于开始日期");
        }
        s.setName(name);
        s.setStartDate(req.startDate());
        s.setEndDate(req.endDate());
        s.setRemark(blank(req.remark()) ? null : req.remark().trim());
    }

    private void saveFollows(Season s, List<Long> followIds) {
        if (followIds == null) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(followIds);
        for (Long prevId : ids) {
            if (prevId.equals(s.getId())) {
                throw ApiException.badRequest("一季不能接着它自己");
            }
            Season prev = get(prevId);
            if (BreedingIndex.BY_START.compare(prev, s) > 0) {
                throw ApiException.badRequest("“" + prev.getName() + "”比这一季开始得晚，不能接着它");
            }
            SeasonFollow f = new SeasonFollow();
            f.setSeasonId(s.getId());
            f.setPrevSeasonId(prevId);
            followMapper.insert(f);
        }
    }

    /** 只要区块划分：去掉格子对应的小区块 id，保存时按新地块重新建小区块 */
    static FieldLayout withoutPlots(FieldLayout l) {
        List<Block> blocks = l.blocks().stream().map(b -> {
            Inner in = b.inner();
            return in == null ? b : new Block(b.id(), b.r(), b.c(), b.name(), b.kind(), b.fit(),
                    new Inner(in.rows(), in.cols(), in.merges(), Map.of(), in.kinds()));
        }).sorted(Comparator.comparingInt(Block::r).thenComparingInt(Block::c)).toList();
        return new FieldLayout(l.version(), l.unit(), l.rows(), l.cols(), l.merges(), blocks, l.nextBlockNo(),
                l.defaultInner(), l.northDeg());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
