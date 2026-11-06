package cn.tudijiance.breeding;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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

import cn.tudijiance.breeding.BreedingDtos.AdoptRequest;
import cn.tudijiance.breeding.BreedingDtos.CandidateGroup;
import cn.tudijiance.breeding.BreedingDtos.Candidates;
import cn.tudijiance.breeding.BreedingDtos.CrossRequest;
import cn.tudijiance.breeding.BreedingDtos.CrossResult;
import cn.tudijiance.breeding.BreedingDtos.FounderItem;
import cn.tudijiance.breeding.BreedingDtos.FoundersRequest;
import cn.tudijiance.breeding.BreedingDtos.GenerationRequest;
import cn.tudijiance.breeding.BreedingDtos.GraphView;
import cn.tudijiance.breeding.BreedingDtos.LineRow;
import cn.tudijiance.breeding.BreedingDtos.LineUpdateRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantResult;
import cn.tudijiance.breeding.BreedingDtos.PlantedIn;
import cn.tudijiance.breeding.BreedingDtos.SelectionRequest;
import cn.tudijiance.breeding.BreedingDtos.StatusRequest;
import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

/**
 * 品系：原始材料、杂交组合、选株后代。编号和系谱式见 {@link Pedigree}，世代记在每季的种植记录上。
 */
@Service
public class LineService {

    public static final String ACTIVE = "ACTIVE";
    public static final String KEPT = "KEPT";
    public static final String DROPPED = "DROPPED";
    public static final String RELEASED = "RELEASED";
    private static final Set<String> STATUSES = Set.of(ACTIVE, KEPT, DROPPED, RELEASED);
    private static final Set<String> KINDS = Set.of(Variety.FOUNDER, Variety.CROSS, Variety.SELECTION);
    private static final Set<String> ECOTYPES = Set.of("SPRING", "WEAK_SPRING", "SEMI_WINTER", "WINTER");
    private static final Set<String> QUALITY_TYPES = Set.of("STRONG", "MEDIUM_STRONG", "MEDIUM", "WEAK");
    /** "最近种过的"往前看几季 */
    private static final int RECENT_SEASONS = 4;

    private final VarietyMapper varietyMapper;
    private final PlantingMapper plantingMapper;
    private final ObservationMapper observationMapper;
    private final SeasonMapper seasonMapper;
    private final FieldService fieldService;
    private final TrialService trialService;
    private final BreedingIndex.Loader loader;
    private final Clock clock;

    public LineService(VarietyMapper varietyMapper, PlantingMapper plantingMapper, ObservationMapper observationMapper,
                       SeasonMapper seasonMapper, FieldService fieldService, TrialService trialService,
                       BreedingIndex.Loader loader, Clock clock) {
        this.varietyMapper = varietyMapper;
        this.plantingMapper = plantingMapper;
        this.observationMapper = observationMapper;
        this.seasonMapper = seasonMapper;
        this.fieldService = fieldService;
        this.trialService = trialService;
        this.loader = loader;
        this.clock = clock;
    }

    // ---------- 查看 ----------

    /** keyword 按名称、曾用名、编号、系谱式找 */
    public List<LineRow> list(String keyword, String status, String kind) {
        BreedingIndex idx = loader.load();
        Rows rows = new Rows(idx);
        String k = keyword == null ? "" : keyword.trim();
        return idx.lines().values().stream()
                .filter(v -> status == null || status.isBlank() || status.equals(statusOf(v)))
                .filter(v -> kind == null || kind.isBlank() || kind.equals(v.getLineKind()))
                .map(rows::row)
                .filter(r -> k.isEmpty() || contains(r.name(), k) || contains(r.code(), k)
                        || contains(r.formerNames(), k) || contains(r.pedigree(), k))
                .sorted(ROW_ORDER)
                .toList();
    }

    public GraphView graph() {
        BreedingIndex idx = loader.load();
        Rows rows = new Rows(idx);
        return new GraphView(idx.ordered(), idx.lines().values().stream().map(rows::row).sorted(ROW_ORDER).toList());
    }

    public Variety require(Long id) {
        Variety v = id == null ? null : varietyMapper.selectById(id);
        if (v == null || !v.isLine()) {
            throw ApiException.notFound("品系");
        }
        return v;
    }

    LineRow row(Long id) {
        BreedingIndex idx = loader.load();
        return new Rows(idx).row(idx.lines().get(id));
    }

    // ---------- 新建 ----------

    /** 新建原始材料：可以一次建多个。编号不填时用名称 */
    @Transactional
    public List<LineRow> createFounders(FoundersRequest req) {
        Set<String> names = new HashSet<>();
        List<Long> ids = new ArrayList<>();
        for (FounderItem item : req.items()) {
            String name = item.name().trim();
            requireName(name, null);
            if (!names.add(name)) {
                throw ApiException.badRequest("名称重复：" + name);
            }
            String code = blank(item.code()) ? name : item.code().trim();
            requireNewCode(code);
            check(item.ecotype(), ECOTYPES, "冬春性");
            check(item.qualityType(), QUALITY_TYPES, "品质类型");
            Variety v = new Variety();
            v.setName(name);
            v.setLineKind(Variety.FOUNDER);
            v.setLineCode(code);
            v.setBreeder(trimOrNull(item.breeder()));
            v.setEcotype(trimOrNull(item.ecotype()));
            v.setQualityType(trimOrNull(item.qualityType()));
            v.setRemark(trimOrNull(item.remark()));
            v.setLineStatus(ACTIVE);
            v.setCreatedAt(LocalDateTime.now(clock));
            varietyMapper.insert(v);
            ids.add(v.getId());
        }
        return rowsOf(ids);
    }

    /** 把原来品种比较里的品种拿来当原始材料（同一条记录，品种页照常能看到） */
    @Transactional
    public List<LineRow> adopt(AdoptRequest req) {
        for (Long id : new LinkedHashSet<>(req.varietyIds())) {
            Variety v = varietyMapper.selectById(id);
            if (v == null) {
                throw ApiException.notFound("品种");
            }
            if (v.isLine()) {
                continue;
            }
            String problem = Pedigree.nameProblem(v.getName());
            if (problem != null) {
                throw ApiException.badRequest(problem + "，请先在品种页改名");
            }
            requireNewCode(v.getName());
            varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                    .set(Variety::getLineKind, Variety.FOUNDER)
                    .set(Variety::getLineCode, v.getName())
                    .set(Variety::getLineStatus, ACTIVE)
                    .eq(Variety::getId, id));
        }
        return rowsOf(req.varietyIds());
    }

    /**
     * 杂交：建一个杂交组合，编号"杂交年份后两位-当年第几个"。同样的父母以前做过时，在 previous 里列出以前的编号
     */
    @Transactional
    public synchronized CrossResult cross(CrossRequest req) {
        Variety mother = require(req.motherId());
        Variety father = require(req.fatherId());
        if (mother.getId().equals(father.getId())) {
            throw ApiException.badRequest("母本和父本不能是同一个品系");
        }
        Season season = season(req.seasonId());
        int year = req.crossYear() != null ? req.crossYear() : season.yearOf();
        int seq = maxSeq(Wrappers.<Variety>lambdaQuery().eq(Variety::getLineKind, Variety.CROSS)
                .eq(Variety::getCrossYear, year)) + 1;
        String code = Pedigree.crossCode(year, seq);
        while (codeExists(code)) {
            code = Pedigree.crossCode(year, ++seq);
        }
        String name = blank(req.name()) ? code : req.name().trim();
        requireName(name, null);

        Variety v = new Variety();
        v.setName(name);
        v.setLineKind(Variety.CROSS);
        v.setLineCode(code);
        v.setSeqNo(seq);
        v.setCrossYear(year);
        v.setMotherId(mother.getId());
        v.setFatherId(father.getId());
        v.setOriginSeasonId(season.getId());
        // 描述属性：父母一样才继承，不一样空着由老师填
        v.setEcotype(same(mother.getEcotype(), father.getEcotype()));
        v.setQualityType(same(mother.getQualityType(), father.getQualityType()));
        v.setBreeder(same(mother.getBreeder(), father.getBreeder()));
        v.setRemark(trimOrNull(req.remark()));
        v.setLineStatus(ACTIVE);
        v.setCreatedAt(LocalDateTime.now(clock));
        varietyMapper.insert(v);

        List<String> previous = varietyMapper.selectList(Wrappers.<Variety>lambdaQuery()
                        .eq(Variety::getLineKind, Variety.CROSS)
                        .eq(Variety::getMotherId, mother.getId()).eq(Variety::getFatherId, father.getId())
                        .ne(Variety::getId, v.getId()).orderByAsc(Variety::getId))
                .stream().map(Variety::getLineCode).toList();
        return new CrossResult(row(v.getId()), previous);
    }

    /**
     * 选株：从品系里选出 count 株，每株建一个选株后代，编号"父编号-第几株"（接着以前选过的往下数）。
     * 在平面图上选时记下从哪个小区块（或整个区块）选的。
     */
    @Transactional
    public synchronized List<LineRow> select(Long parentId, SelectionRequest req) {
        Variety parent = require(parentId);
        BreedingIndex idx = loader.load();
        Places places = new Places(fieldService, idx.fields());
        Long seasonId;
        Long fieldId = null;
        String blockId = null;
        Long plotId = null;
        if (req.plotId() != null) {
            Plot plot = plotOf(req.plotId());
            Field f = idx.fields().get(plot.getFieldId());
            if (f == null) {
                throw ApiException.badRequest("只能从育种的地块里选株");
            }
            Planting planting = plantingAt(idx, f.getId(), plot.getId());
            if (planting == null || !parentId.equals(planting.getVarietyId())) {
                throw ApiException.badRequest("这个小区块种的不是“" + parent.getName() + "”");
            }
            seasonId = f.getSeasonId();
            fieldId = f.getId();
            plotId = plot.getId();
            String[] block = places.blocks(fieldId).get(plotId);
            blockId = block == null ? null : block[0];
        } else if (req.fieldId() != null) {
            Field f = idx.fields().get(req.fieldId());
            if (f == null) {
                throw ApiException.badRequest("只能从育种的地块里选株");
            }
            if (blank(req.blockId())) {
                throw ApiException.badRequest("请选择从哪个区块选的");
            }
            List<Long> inBlock = places.plotsInBlock(f.getId(), req.blockId());
            boolean planted = inBlock.stream().map(id -> plantingAt(idx, f.getId(), id))
                    .anyMatch(p -> p != null && parentId.equals(p.getVarietyId()));
            if (!planted) {
                throw ApiException.badRequest("这个区块里没有种“" + parent.getName() + "”");
            }
            seasonId = f.getSeasonId();
            fieldId = f.getId();
            blockId = req.blockId();
        } else {
            if (req.seasonId() == null) {
                throw ApiException.badRequest("请选择是哪一季选的");
            }
            seasonId = season(req.seasonId()).getId();
        }

        int seq = maxSeq(Wrappers.<Variety>lambdaQuery().eq(Variety::getParentId, parentId)) + 1;
        List<String> tags = req.tagNos() == null ? List.of() : req.tagNos();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < req.count(); i++) {
            String code = Pedigree.selectionCode(parent.getLineCode(), seq);
            while (codeExists(code)) {
                code = Pedigree.selectionCode(parent.getLineCode(), ++seq);
            }
            requireName(code, null);
            Variety v = new Variety();
            v.setName(code);
            v.setLineKind(Variety.SELECTION);
            v.setLineCode(code);
            v.setSeqNo(seq);
            v.setParentId(parentId);
            v.setOriginSeasonId(seasonId);
            v.setOriginFieldId(fieldId);
            v.setOriginBlockId(blockId);
            v.setOriginPlotId(plotId);
            v.setTagNo(i < tags.size() ? trimOrNull(tags.get(i)) : null);
            v.setEcotype(parent.getEcotype());
            v.setQualityType(parent.getQualityType());
            v.setBreeder(parent.getBreeder());
            v.setLineStatus(ACTIVE);
            v.setCreatedAt(LocalDateTime.now(clock));
            varietyMapper.insert(v);
            ids.add(v.getId());
            seq++;
        }
        return rowsOf(ids);
    }

    // ---------- 修改 ----------

    /** 改名后旧名记成曾用名；编号不变 */
    @Transactional
    public LineRow update(Long id, LineUpdateRequest req) {
        Variety v = require(id);
        String name = req.name().trim();
        String former = v.getFormerNames();
        if (!name.equals(v.getName())) {
            requireName(name, id);
            former = appendFormer(former, v.getName(), v.getLineCode());
        }
        check(req.ecotype(), ECOTYPES, "冬春性");
        check(req.qualityType(), QUALITY_TYPES, "品质类型");
        varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                .set(Variety::getName, name)
                .set(Variety::getFormerNames, former)
                .set(Variety::getBreeder, trimOrNull(req.breeder()))
                .set(Variety::getEcotype, trimOrNull(req.ecotype()))
                .set(Variety::getQualityType, trimOrNull(req.qualityType()))
                .set(Variety::getRemark, trimOrNull(req.remark()))
                .eq(Variety::getId, id));
        return row(id);
    }

    @Transactional
    public List<LineRow> setStatus(StatusRequest req) {
        if (!STATUSES.contains(req.status())) {
            throw ApiException.badRequest("状态只能是 ACTIVE、KEPT、DROPPED 或 RELEASED");
        }
        req.lineIds().forEach(this::require);
        varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                .set(Variety::getLineStatus, req.status()).in(Variety::getId, req.lineIds()));
        return rowsOf(req.lineIds());
    }

    /** 有子代、种过的品系不能删，只能淘汰 */
    @Transactional
    public void delete(Long id) {
        Variety v = require(id);
        long children = varietyMapper.selectCount(Wrappers.<Variety>lambdaQuery()
                .eq(Variety::getMotherId, id).or().eq(Variety::getFatherId, id).or().eq(Variety::getParentId, id));
        if (children > 0) {
            throw ApiException.conflict("“" + v.getName() + "”有 " + children + " 个子代，不能删除，可以标成淘汰");
        }
        long planted = plantingMapper.selectCount(Wrappers.<Planting>lambdaQuery().eq(Planting::getVarietyId, id));
        if (planted > 0) {
            throw ApiException.conflict("“" + v.getName() + "”已经种在 " + planted + " 个小区块里，不能删除，可以标成淘汰");
        }
        varietyMapper.deleteById(id);
    }

    // ---------- 种植 ----------

    /**
     * 地块里种植的下拉框：接着的那一季（或几季）种过的品系、它们的子代、这一季已经种了的、最近种过的。
     * "全新开始"的季只列这一季已经种了的，其余靠搜索
     */
    public Candidates candidates(Long fieldId) {
        BreedingIndex idx = loader.load();
        Field field = land(idx, fieldId);
        Season season = idx.seasons().get(field.getSeasonId());
        Rows rows = new Rows(idx);
        List<Long> prev = idx.follows().getOrDefault(season.getId(), List.of());

        Set<Long> prevLines = new LinkedHashSet<>();
        prev.forEach(s -> prevLines.addAll(idx.linesIn(s)));
        prevLines.removeIf(id -> DROPPED.equals(statusOf(idx.lines().get(id))));
        Set<Long> children = new LinkedHashSet<>();
        prevLines.forEach(id -> idx.childrenOf(id).forEach(c -> {
            if (!prevLines.contains(c.getId()) && !DROPPED.equals(statusOf(c))) {
                children.add(c.getId());
            }
        }));
        Set<Long> thisSeason = idx.linesIn(season.getId());
        Set<Long> recent = new LinkedHashSet<>();
        if (!prev.isEmpty()) {
            idx.before(season).stream().limit(RECENT_SEASONS).forEach(s -> idx.linesIn(s.getId()).forEach(id -> {
                if (!prevLines.contains(id) && !children.contains(id) && !thisSeason.contains(id)
                        && !DROPPED.equals(statusOf(idx.lines().get(id)))) {
                    recent.add(id);
                }
            }));
        }
        String prevNames = prev.stream().map(idx.seasons()::get).filter(Objects::nonNull)
                .sorted(BreedingIndex.BY_START).map(Season::getName).collect(Collectors.joining("、"));
        List<CandidateGroup> groups = new ArrayList<>();
        if (!prev.isEmpty()) {
            groups.add(new CandidateGroup("prev", prevNames + " 种过的", sorted(prevLines, rows)));
            groups.add(new CandidateGroup("children", "它们的子代", sorted(children, rows)));
        }
        groups.add(new CandidateGroup("season", "这一季已经种了的", sorted(thisSeason, rows)));
        if (!prev.isEmpty()) {
            groups.add(new CandidateGroup("recent", "最近种过的", sorted(recent, rows)));
        }
        return new Candidates(season.getId(), prev.isEmpty(), groups);
    }

    /**
     * 种植：给选中的小区块种上品系（整块种就是选中区块里的全部小区块），lineId 为空表示清掉。
     * 录了数据的小区块不能改种；世代按以前种的自动算。
     */
    @Transactional
    public PlantResult plant(Long fieldId, PlantRequest req) {
        BreedingIndex idx = loader.load();
        Field field = land(idx, fieldId);
        Trial trial = idx.trialByField().get(fieldId);
        Season season = idx.seasons().get(field.getSeasonId());
        Variety line = req.lineId() == null ? null : require(req.lineId());
        List<Plot> plots = fieldService.requireInField(fieldId, req.plotIds());
        if (line != null && plots.stream().anyMatch(p -> !Plot.KIND_PLOT.equals(p.getKind()))) {
            throw ApiException.badRequest("道路、保护区、空地不能种");
        }

        Map<Long, Planting> existing = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                        .eq(Planting::getTrialId, trial.getId()).in(Planting::getPlotId, req.plotIds()))
                .stream().collect(Collectors.toMap(Planting::getPlotId, p -> p));
        List<Planting> changed = existing.values().stream()
                .filter(p -> p.getVarietyId() != null && !Objects.equals(p.getVarietyId(), req.lineId())).toList();
        if (!changed.isEmpty()) {
            Set<Long> withData = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                            .select(Observation::getPlantingId)
                            .in(Observation::getPlantingId, changed.stream().map(Planting::getId).toList())
                            .eq(Observation::getDeleted, false))
                    .stream().map(Observation::getPlantingId).collect(Collectors.toSet());
            if (!withData.isEmpty()) {
                Map<Long, Plot> byId = plots.stream().collect(Collectors.toMap(Plot::getId, p -> p));
                String codes = changed.stream().filter(p -> withData.contains(p.getId())).limit(6)
                        .map(p -> byId.get(p.getPlotId()).getCode()).collect(Collectors.joining("、"));
                throw ApiException.conflict(codes + " 号小区块已经录了数据，不能改种。录错了请先删掉数据或提交更正申请");
            }
        }

        Integer generation = line == null ? null : defaultGeneration(line, season, idx);
        trialService.assignVariety(trial.getId(), req.plotIds(), req.lineId());
        plantingMapper.update(null, Wrappers.<Planting>lambdaUpdate()
                .eq(Planting::getTrialId, trial.getId()).in(Planting::getPlotId, req.plotIds())
                .set(Planting::getGeneration, generation));
        List<String> notes = new ArrayList<>();
        if (line != null && DROPPED.equals(line.getLineStatus())) {
            varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                    .set(Variety::getLineStatus, ACTIVE).eq(Variety::getId, line.getId()));
            notes.add("“" + line.getName() + "”原来标的是淘汰，种下去后改回在选");
        }
        return new PlantResult(trialService.layout(trial.getId()), notes);
    }

    /** 手动改世代（比如用的是存的老种子） */
    @Transactional
    public PlantResult setGeneration(Long fieldId, GenerationRequest req) {
        BreedingIndex idx = loader.load();
        land(idx, fieldId);
        Trial trial = idx.trialByField().get(fieldId);
        fieldService.requireInField(fieldId, req.plotIds());
        plantingMapper.update(null, Wrappers.<Planting>lambdaUpdate()
                .eq(Planting::getTrialId, trial.getId()).in(Planting::getPlotId, req.plotIds())
                .isNotNull(Planting::getVarietyId)
                .set(Planting::getGeneration, req.generation()));
        return new PlantResult(trialService.layout(trial.getId()), List.of());
    }

    /**
     * 世代：原始材料不标；这一季别的地方种过就用那里的；以前种过就是上一次的加 1；
     * 第一次种：杂交组合是 F1，选株后代是父本选出那一季的世代加 1
     */
    static Integer defaultGeneration(Variety line, Season season, BreedingIndex idx) {
        if (!Variety.CROSS.equals(line.getLineKind()) && !Variety.SELECTION.equals(line.getLineKind())) {
            return null;
        }
        List<Planting> mine = idx.plantingsOf(line.getId());
        for (Planting p : mine) {
            if (season.getId().equals(idx.seasonOf(p)) && p.getGeneration() != null) {
                return p.getGeneration();
            }
        }
        Planting last = mine.stream()
                .filter(p -> BreedingIndex.BY_START.compare(idx.seasons().get(idx.seasonOf(p)), season) < 0)
                .max(Comparator.comparing((Planting p) -> idx.seasons().get(idx.seasonOf(p)), BreedingIndex.BY_START)
                        .thenComparing(p -> p.getGeneration() == null ? 0 : p.getGeneration()))
                .orElse(null);
        if (last != null) {
            return last.getGeneration() == null ? null : last.getGeneration() + 1;
        }
        if (Variety.CROSS.equals(line.getLineKind())) {
            return 1;
        }
        Variety parent = idx.lines().get(line.getParentId());
        if (parent == null) {
            return null;
        }
        Integer parentGen = idx.plantingsOf(parent.getId()).stream()
                .filter(p -> line.getOriginSeasonId() == null || line.getOriginSeasonId().equals(idx.seasonOf(p)))
                .map(Planting::getGeneration).filter(Objects::nonNull).max(Integer::compare).orElse(null);
        return parentGen == null ? null : parentGen + 1;
    }

    /** 新的一季布置好以后：接着的季里种过、这一季没再种、也没有子代的在选品系，给老师勾选淘汰还是保留 */
    public List<LineRow> cleanup(Long seasonId) {
        BreedingIndex idx = loader.load();
        Season season = idx.seasons().get(seasonId);
        if (season == null) {
            throw ApiException.notFound("季");
        }
        Rows rows = new Rows(idx);
        Set<Long> thisSeason = idx.linesIn(seasonId);
        Set<Long> result = new LinkedHashSet<>();
        idx.follows().getOrDefault(seasonId, List.of()).forEach(s -> idx.linesIn(s).forEach(id -> {
            Variety v = idx.lines().get(id);
            if (v != null && !thisSeason.contains(id) && idx.childrenOf(id).isEmpty() && ACTIVE.equals(statusOf(v))) {
                result.add(id);
            }
        }));
        return sorted(result, rows);
    }

    // ---------- 行 ----------

    static final Comparator<LineRow> ROW_ORDER = Comparator
            .comparing((LineRow r) -> r.code() == null ? "" : r.code(), LineService::naturalCompare);

    /** 把一批品系转成行，系谱式等一次算好 */
    final class Rows {
        private final BreedingIndex idx;
        private final Map<Long, String> pedigree;

        Rows(BreedingIndex idx) {
            this.idx = idx;
            this.pedigree = Pedigree.all(idx.lines());
        }

        LineRow row(Variety v) {
            Map<Long, Integer> planted = idx.plantedSeasons(v.getId());
            Long home = v.getOriginSeasonId();
            if (home == null && !planted.isEmpty()) {
                home = planted.keySet().iterator().next();
            }
            return new LineRow(v.getId(), v.getName(), v.getLineCode(), v.getLineKind(), statusOf(v),
                    pedigree.get(v.getId()), v.getMotherId(), v.getFatherId(), v.getParentId(), parentText(v),
                    v.getFormerNames(), v.getOriginSeasonId(), home,
                    planted.entrySet().stream().map(e -> new PlantedIn(e.getKey(), e.getValue())).toList(),
                    idx.childrenOf(v.getId()).size(), v.getEcotype(), v.getQualityType(), v.getBreeder(),
                    v.getRemark(), v.getTagNo(), v.getSeqNo());
        }

        private String parentText(Variety v) {
            Map<Long, Variety> lines = idx.lines();
            if (Variety.CROSS.equals(v.getLineKind())) {
                return nameOf(lines.get(v.getMotherId())) + " × " + nameOf(lines.get(v.getFatherId()));
            }
            if (Variety.SELECTION.equals(v.getLineKind())) {
                return "选自 " + nameOf(lines.get(v.getParentId()));
            }
            return "原始材料";
        }
    }

    private List<LineRow> rowsOf(List<Long> ids) {
        BreedingIndex idx = loader.load();
        Rows rows = new Rows(idx);
        return ids.stream().distinct().map(idx.lines()::get).filter(Objects::nonNull).map(rows::row).toList();
    }

    private static List<LineRow> sorted(Set<Long> ids, Rows rows) {
        return ids.stream().map(rows.idx.lines()::get).filter(Objects::nonNull).map(rows::row).sorted(ROW_ORDER).toList();
    }

    // ---------- 工具 ----------

    static String statusOf(Variety v) {
        return v == null || v.getLineStatus() == null ? ACTIVE : v.getLineStatus();
    }

    Field land(BreedingIndex idx, Long fieldId) {
        Field f = idx.fields().get(fieldId);
        if (f == null) {
            throw ApiException.badRequest("这不是育种的地块");
        }
        if (idx.trialByField().get(fieldId) == null) {
            throw ApiException.conflict("这块地块缺少育种试验，请重新建地块");
        }
        return f;
    }

    private Season season(Long id) {
        Season s = id == null ? null : seasonMapper.selectById(id);
        if (s == null) {
            throw ApiException.notFound("季");
        }
        return s;
    }

    private Plot plotOf(Long plotId) {
        Plot plot = fieldService.plotById(plotId);
        if (plot == null) {
            throw ApiException.notFound("小区块");
        }
        return plot;
    }

    private static Planting plantingAt(BreedingIndex idx, Long fieldId, Long plotId) {
        Trial t = idx.trialByField().get(fieldId);
        if (t == null) {
            return null;
        }
        return idx.plantings().stream().filter(p -> t.getId().equals(p.getTrialId()) && plotId.equals(p.getPlotId()))
                .findFirst().orElse(null);
    }

    private int maxSeq(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variety> query) {
        return varietyMapper.selectList(query.select(Variety::getSeqNo)).stream()
                .map(Variety::getSeqNo).filter(Objects::nonNull).max(Integer::compare).orElse(0);
    }

    private boolean codeExists(String code) {
        return varietyMapper.selectCount(Wrappers.<Variety>lambdaQuery().eq(Variety::getLineCode, code)) > 0;
    }

    private void requireNewCode(String code) {
        if (code.length() > 64) {
            throw ApiException.badRequest("编号不能超过 64 个字：" + code);
        }
        if (codeExists(code)) {
            throw ApiException.conflict("编号“" + code + "”已经有了");
        }
    }

    /** 名称：不能有"/""*"，全系统唯一（和品种一起算） */
    private void requireName(String name, Long exceptId) {
        String problem = Pedigree.nameProblem(name);
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
        if (name.length() > 100) {
            throw ApiException.badRequest("名称不能超过 100 个字");
        }
        var query = Wrappers.<Variety>lambdaQuery().eq(Variety::getName, name);
        if (exceptId != null) {
            query.ne(Variety::getId, exceptId);
        }
        if (varietyMapper.selectCount(query) > 0) {
            throw ApiException.conflict("已经有叫“" + name + "”的品系或品种");
        }
    }

    public static String appendFormer(String former, String oldName, String code) {
        if (oldName == null || oldName.equals(code)) {
            return former;
        }
        List<String> list = former == null || former.isBlank() ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(former.split("、")));
        if (!list.contains(oldName)) {
            list.add(oldName);
        }
        String joined = String.join("、", list);
        return joined.length() > 1000 ? joined.substring(joined.length() - 1000) : joined;
    }

    private static String nameOf(Variety v) {
        return v == null ? "？" : v.getName();
    }

    private static String same(String a, String b) {
        return a != null && a.equals(b) ? a : null;
    }

    private static boolean contains(String s, String k) {
        return s != null && s.contains(k);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static void check(String value, Set<String> allowed, String what) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            throw ApiException.badRequest(what + "的取值不正确：" + value);
        }
    }

    static boolean isKind(String kind) {
        return KINDS.contains(kind);
    }

    /** 编号按"数字段按数值"排：25-002 在 25-010 前面，25-001-2 在 25-001-10 前面 */
    static int naturalCompare(String a, String b) {
        String[] pa = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] pb = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            String x = pa[i];
            String y = pb[i];
            int c;
            if (!x.isEmpty() && !y.isEmpty() && Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))) {
                c = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    /** 给 LineDetailService 用 */
    Map<Long, LineRow> rowsById(BreedingIndex idx) {
        Rows rows = new Rows(idx);
        Map<Long, LineRow> map = new LinkedHashMap<>();
        idx.lines().values().forEach(v -> map.put(v.getId(), rows.row(v)));
        return map;
    }
}
