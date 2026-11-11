package cn.tudijiance.field;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.FieldDtos.FieldDetail;
import cn.tudijiance.field.FieldLayout.Block;
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.LayoutGeometry.Flat;
import cn.tudijiance.field.LayoutGeometry.Leaf;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.trial.ControlLinkPlot;
import cn.tudijiance.trial.ControlLinkPlotMapper;
import cn.tudijiance.field.FieldDtos.FieldRequest;
import cn.tudijiance.field.FieldDtos.PlotPatchRequest;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FieldService {

    private static final Set<String> KINDS = Set.of(Plot.KIND_PLOT, Plot.KIND_GUARD, Plot.KIND_PATH, Plot.KIND_BLANK);

    private final FieldMapper fieldMapper;
    private final PlotMapper plotMapper;
    private final PlantingMapper plantingMapper;
    private final ObservationMapper observationMapper;
    private final ControlLinkPlotMapper linkPlotMapper;
    private final JsonMapper json;
    private final Clock clock;

    public FieldService(FieldMapper fieldMapper, PlotMapper plotMapper, PlantingMapper plantingMapper,
                        ObservationMapper observationMapper, ControlLinkPlotMapper linkPlotMapper, JsonMapper json,
                        Clock clock) {
        this.fieldMapper = fieldMapper;
        this.plotMapper = plotMapper;
        this.plantingMapper = plantingMapper;
        this.observationMapper = observationMapper;
        this.linkPlotMapper = linkPlotMapper;
        this.json = json;
        this.clock = clock;
    }

    public List<Field> list() {
        return list(null);
    }

    /**
     * scope：为空或 all 全部；breeding 只列属于某一季的地块；comparison 只列没分季的老实验田（直接用接口建的）
     */
    public List<Field> list(String scope) {
        var query = Wrappers.<Field>lambdaQuery().orderByAsc(Field::getId);
        if ("comparison".equals(scope)) {
            query.isNull(Field::getSeasonId);
        } else if ("breeding".equals(scope)) {
            query.isNotNull(Field::getSeasonId);
        } else if (scope != null && !scope.isBlank() && !"all".equals(scope)) {
            throw ApiException.badRequest("scope 只能是 all、breeding 或 comparison");
        }
        return fieldMapper.selectList(query);
    }

    /**
     * "同一块地"的各季：地块按名称连起来（每季照着上一季建的同名地块，比如各季的"东地"），
     * 看历年变化、自助分析按地块筛选时用；没分季的老实验田就是它自己
     */
    public List<Long> sameLandIds(Long fieldId) {
        Field field = get(fieldId);
        if (field.getSeasonId() == null) {
            return List.of(fieldId);
        }
        return fieldMapper.selectList(Wrappers.<Field>lambdaQuery()
                        .isNotNull(Field::getSeasonId).eq(Field::getName, field.getName()))
                .stream().map(Field::getId).toList();
    }

    /** 找不到时返回 null */
    public Plot plotById(Long id) {
        return id == null ? null : plotMapper.selectById(id);
    }

    /** 找不到时返回 null */
    public Field find(Long id) {
        return id == null ? null : fieldMapper.selectById(id);
    }

    public Field get(Long id) {
        Field field = fieldMapper.selectById(id);
        if (field == null) {
            throw ApiException.notFound("实验田");
        }
        return field;
    }

    public FieldDetail detail(Long id) {
        Field field = get(id);
        List<Plot> plots = plots(id);
        FieldLayout saved = savedLayout(field);
        FieldLayout shown = withKinds(saved != null ? saved : LayoutGeometry.legacy(field, plots), plots);
        return new FieldDetail(field, plots, shown, saved != null);
    }

    /**
     * 格子类型以小区块类型为准：在"布置试验"里改了小区块类型（保护区、道路……）后，布局里的格子跟着显示，
     * 编辑地块时拿到的草稿也是对的
     */
    static FieldLayout withKinds(FieldLayout l, List<Plot> plots) {
        Map<Long, String> kindById = new HashMap<>();
        plots.forEach(p -> kindById.put(p.getId(), p.getKind()));
        List<Block> blocks = l.blocks().stream().map(b -> {
            Inner in = b.inner();
            if (in == null) {
                return b;
            }
            Map<String, String> kinds = new LinkedHashMap<>();
            if (in.kinds() != null) {
                in.kinds().forEach((k, v) -> {
                    if (!FieldLayout.PLANT.equals(v)) {
                        kinds.put(k, v);
                    }
                });
            }
            if (in.plots() != null) {
                in.plots().forEach((k, id) -> {
                    String kind = kindById.get(id);
                    if (kind == null) {
                        return;
                    }
                    String cell = Plot.cellKindOf(kind);
                    if (FieldLayout.PLANT.equals(cell)) {
                        kinds.remove(k);
                    } else {
                        kinds.put(k, cell);
                    }
                });
            }
            return b.withInner(new Inner(in.rows(), in.cols(), in.merges(), in.plots(), kinds.isEmpty() ? null : kinds));
        }).toList();
        return l.withBlocks(blocks);
    }

    /** 保存过的区块布局；还是按行列建的老网格时为 null */
    public FieldLayout savedLayout(Field field) {
        return field.getLayout() == null || field.getLayout().isBlank()
                ? null : json.readValue(field.getLayout(), FieldLayout.class);
    }

    /** 几块地的小区块（一次查询） */
    public List<Plot> plotsOf(java.util.Collection<Long> fieldIds) {
        if (fieldIds.isEmpty()) {
            return List.of();
        }
        return plotMapper.selectList(Wrappers.<Plot>lambdaQuery().in(Plot::getFieldId, fieldIds));
    }

    public List<Plot> plots(Long fieldId) {
        return plotMapper.selectList(Wrappers.<Plot>lambdaQuery()
                .eq(Plot::getFieldId, fieldId)
                .orderByAsc(Plot::getRowNo, Plot::getColNo));
    }

    /** 新建实验田，并按行顺序生成小区块，默认编号 1、2、3…… */
    @Transactional
    public FieldDetail create(FieldRequest req) {
        Field field = new Field();
        apply(field, req);
        LocalDateTime now = LocalDateTime.now(clock);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        fieldMapper.insert(field);
        if (req.layout() != null) {
            return saveLayout(field.getId(), req.layout());
        }
        int seq = 1;
        List<Plot> plots = new ArrayList<>();
        for (int r = 1; r <= req.rowCount(); r++) {
            for (int c = 1; c <= req.colCount(); c++) {
                plots.add(newPlot(field.getId(), r, c, String.valueOf(seq++), req));
            }
        }
        insertPlots(plots);
        return detail(field.getId());
    }

    /** 修改实验田信息。行列数变小时，删掉网格外的小区块；这些小区块已经种了品种的话不允许缩小。 */
    @Transactional
    public FieldDetail update(Long id, FieldRequest req) {
        Field field = get(id);
        int oldRows = field.getRowCount();
        int oldCols = field.getColCount();
        apply(field, req);
        field.setUpdatedAt(LocalDateTime.now(clock));
        // 有区块布局的实验田，小区块由布局决定，这里不改行列
        if (field.getLayout() != null) {
            field.setRowCount(oldRows);
            field.setColCount(oldCols);
            fieldMapper.updateById(field);
            return detail(id);
        }
        fieldMapper.updateById(field);

        List<Plot> outside = plots(id).stream()
                .filter(p -> p.getRowNo() > req.rowCount() || p.getColNo() > req.colCount())
                .toList();
        if (!outside.isEmpty()) {
            List<Long> ids = outside.stream().map(Plot::getId).toList();
            long used = plantingMapper.selectCount(Wrappers.<Planting>lambdaQuery().in(Planting::getPlotId, ids));
            if (used > 0) {
                throw ApiException.conflict("要删除的小区块里已经分配了品种，不能缩小网格");
            }
            plotMapper.deleteByIds(ids);
        }
        List<Plot> added = new ArrayList<>();
        for (int r = 1; r <= req.rowCount(); r++) {
            for (int c = 1; c <= req.colCount(); c++) {
                if (r > oldRows || c > oldCols) {
                    added.add(newPlot(id, r, c, null, req));
                }
            }
        }
        insertPlots(added);
        return detail(id);
    }

    @Transactional
    public void delete(Long id) {
        Field field = get(id);
        if (field.getSeasonId() != null) {
            throw ApiException.badRequest("这是育种的地块，请在“季和地块”里删除");
        }
        fieldMapper.deleteById(id);
    }

    /** 删除育种的地块（由 SeasonService 先删掉它的试验） */
    @Transactional
    public void deleteLand(Long id) {
        fieldMapper.deleteById(id);
    }

    /**
     * 批量修改编号。先检查修改后整块田里没有重复编号，再分两步更新（先清空再写入），
     * 这样两个小区块互换编号时不会撞上唯一约束。
     */
    @Transactional
    public List<Plot> updateCodes(Long fieldId, Map<Long, String> codes) {
        get(fieldId);
        List<Plot> plots = plots(fieldId);
        Map<Long, Plot> byId = new HashMap<>();
        plots.forEach(p -> byId.put(p.getId(), p));

        Map<Long, String> normalized = new HashMap<>();
        codes.forEach((plotId, code) -> {
            if (!byId.containsKey(plotId)) {
                throw ApiException.badRequest("小区块 " + plotId + " 不属于这块实验田");
            }
            String c = code == null ? null : code.trim();
            if (c != null && c.length() > 32) {
                throw ApiException.badRequest("编号不能超过 32 个字符：" + c);
            }
            normalized.put(plotId, c == null || c.isEmpty() ? null : c);
        });

        Set<String> seen = new HashSet<>();
        for (Plot p : plots) {
            String finalCode = normalized.containsKey(p.getId()) ? normalized.get(p.getId()) : p.getCode();
            if (finalCode != null && !seen.add(finalCode)) {
                throw ApiException.badRequest("编号重复：" + finalCode);
            }
        }

        List<Long> ids = new ArrayList<>(normalized.keySet());
        plotMapper.update(null, Wrappers.<Plot>lambdaUpdate().set(Plot::getCode, null).in(Plot::getId, ids));
        normalized.forEach((plotId, code) -> {
            if (code != null) {
                plotMapper.update(null, Wrappers.<Plot>lambdaUpdate().set(Plot::getCode, code).eq(Plot::getId, plotId));
            }
        });
        return plots(fieldId);
    }

    @Transactional
    public List<Plot> patchPlots(Long fieldId, PlotPatchRequest req) {
        Field field = get(fieldId);
        if (field.getLayout() != null && (req.lengthM() != null || req.widthM() != null)) {
            throw ApiException.badRequest("这块地的小区块大小由区块布局决定，请在平面图的“编辑地块”里改");
        }
        if (req.kind() != null && !KINDS.contains(req.kind())) {
            throw ApiException.badRequest("小区块类型只能是 PLOT、GUARD、PATH 或 BLANK");
        }
        requireInField(fieldId, req.plotIds());
        if (req.kind() != null && !Plot.KIND_PLOT.equals(req.kind())) {
            long used = plantingMapper.selectCount(Wrappers.<Planting>lambdaQuery()
                    .in(Planting::getPlotId, req.plotIds())
                    .isNotNull(Planting::getVarietyId));
            if (used > 0) {
                throw ApiException.conflict("选中的小区块已经分配了品种，请先清除品种再改成保护区或走道");
            }
        }
        var update = Wrappers.<Plot>lambdaUpdate().in(Plot::getId, req.plotIds());
        boolean any = false;
        if (req.kind() != null) {
            update.set(Plot::getKind, req.kind());
            any = true;
        }
        if (req.lengthM() != null) {
            update.set(Plot::getLengthM, req.lengthM());
            any = true;
        }
        if (req.widthM() != null) {
            update.set(Plot::getWidthM, req.widthM());
            any = true;
        }
        if (req.plantingRows() != null) {
            update.set(Plot::getPlantingRows, req.plantingRows());
            any = true;
        }
        if (req.rowSpacingCm() != null) {
            update.set(Plot::getRowSpacingCm, req.rowSpacingCm());
            any = true;
        }
        if (!any) {
            throw ApiException.badRequest("没有要修改的内容");
        }
        plotMapper.update(null, update);
        return plots(fieldId);
    }

    /**
     * 保存区块布局，并按布局更新小区块：布局里带着小区块 id 的格子更新它的位置和大小，没带的新建小区块，
     * 布局里没有了的小区块删掉——但已经分配了品种、设了重复、录了数据或设了对照的小区块不能删，要先清掉。
     * 新小区块按顺序编号（用没被占用的最小数字）。
     */
    @Transactional
    public FieldDetail saveLayout(Long fieldId, FieldLayout layout) {
        Field field = get(fieldId);
        LayoutGeometry.validate(layout);
        Flat flat = LayoutGeometry.flatten(layout);
        List<Plot> existing = plots(fieldId);
        Map<Long, Plot> byId = existing.stream().collect(Collectors.toMap(Plot::getId, p -> p));

        Set<Long> kept = new HashSet<>();
        for (Leaf leaf : flat.leaves()) {
            if (leaf.widthM() > 9999 || leaf.lengthM() > 9999) {
                throw ApiException.badRequest("小区块太大了：长和宽不能超过 9999 米");
            }
            if (leaf.plotId() == null) {
                continue;
            }
            if (!byId.containsKey(leaf.plotId())) {
                throw ApiException.conflict("实验田的小区块刚刚被别人改过，请刷新后再改布局");
            }
            if (!kept.add(leaf.plotId())) {
                throw ApiException.badRequest("同一个小区块在布局里出现了两次");
            }
        }
        List<Plot> removed = existing.stream().filter(p -> !kept.contains(p.getId())).toList();
        removePlots(removed);
        checkKindChanges(flat, byId);

        // 先把留下的小区块挪到临时位置（负数），再写新位置，免得互换位置时撞上唯一约束
        for (Long id : kept) {
            plotMapper.update(null, Wrappers.<Plot>lambdaUpdate()
                    .set(Plot::getRowNo, -id).set(Plot::getColNo, -id).eq(Plot::getId, id));
        }
        Set<String> usedCodes = new HashSet<>();
        kept.forEach(id -> {
            String code = byId.get(id).getCode();
            if (code != null) {
                usedCodes.add(code);
            }
        });
        int nextCode = 1;
        List<Plot> created = new ArrayList<>();
        for (Leaf leaf : flat.leaves()) {
            BigDecimal width = meters(leaf.widthM());
            BigDecimal length = meters(leaf.lengthM());
            if (leaf.plotId() != null) {
                plotMapper.update(null, Wrappers.<Plot>lambdaUpdate()
                        .set(Plot::getRowNo, leaf.rowNo()).set(Plot::getColNo, leaf.colNo())
                        .set(Plot::getLengthM, length).set(Plot::getWidthM, width)
                        .set(Plot::getKind, leaf.plotKind())
                        .eq(Plot::getId, leaf.plotId()));
                continue;
            }
            while (usedCodes.contains(String.valueOf(nextCode))) {
                nextCode++;
            }
            String code = String.valueOf(nextCode);
            usedCodes.add(code);
            Plot plot = new Plot();
            plot.setFieldId(fieldId);
            plot.setRowNo(leaf.rowNo());
            plot.setColNo(leaf.colNo());
            plot.setCode(code);
            plot.setKind(leaf.plotKind());
            plot.setLengthM(length);
            plot.setWidthM(width);
            created.add(plot);
        }
        insertPlots(created);

        // 新建的小区块按位置找回 id，写进布局
        Map<String, Long> idAt = new HashMap<>();
        plots(fieldId).forEach(p -> idAt.put(p.getRowNo() + "," + p.getColNo(), p.getId()));
        Map<String, Map<String, Long>> idsByBlock = new LinkedHashMap<>();
        for (Leaf leaf : flat.leaves()) {
            idsByBlock.computeIfAbsent(leaf.block().id(), k -> new LinkedHashMap<>())
                    .put(leaf.key(), idAt.get(leaf.rowNo() + "," + leaf.colNo()));
        }
        List<Block> blocks = layout.blocks().stream().map(b -> {
            Block at = new Block(b.id(), b.name(), b.kind(), round4(b.x()), round4(b.y()), round4(b.w()), round4(b.h()), null);
            if (!FieldLayout.PLANT.equals(b.kind())) {
                return at;
            }
            Inner in = b.inner();
            Map<String, String> kinds = new LinkedHashMap<>();
            if (in.kinds() != null) {
                in.kinds().forEach((k, v) -> {
                    if (!FieldLayout.PLANT.equals(v)) {
                        kinds.put(k, v);
                    }
                });
            }
            return at.withInner(new Inner(in.rows(), in.cols(), in.merges() == null ? List.of() : in.merges(),
                    idsByBlock.getOrDefault(b.id(), Map.of()), kinds.isEmpty() ? null : kinds));
        }).toList();
        FieldLayout saved = new FieldLayout(FieldLayout.VERSION, layout.unit(), round4(layout.w()), round4(layout.h()),
                blocks, layout.nextBlockNo(), layout.defaultInner(), layout.northDeg() == null ? 0.0 : layout.northDeg());

        field.setLayout(json.writeValueAsString(saved));
        field.setRowCount(flat.rowCount());
        field.setColCount(flat.colCount());
        field.setUpdatedAt(LocalDateTime.now(clock));
        fieldMapper.updateById(field);
        return detail(fieldId);
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    /** 已经分配了品种的小区块不能改成道路、保护区、空地 */
    private void checkKindChanges(Flat flat, Map<Long, Plot> byId) {
        List<Long> toNonPlot = flat.leaves().stream()
                .filter(leaf -> leaf.plotId() != null && !Plot.KIND_PLOT.equals(leaf.plotKind())
                        && Plot.KIND_PLOT.equals(byId.get(leaf.plotId()).getKind()))
                .map(Leaf::plotId).toList();
        if (toNonPlot.isEmpty()) {
            return;
        }
        List<Long> busy = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                        .in(Planting::getPlotId, toNonPlot).isNotNull(Planting::getVarietyId))
                .stream().map(Planting::getPlotId).distinct().toList();
        if (!busy.isEmpty()) {
            String codes = busy.stream().limit(6).map(id -> {
                Plot p = byId.get(id);
                return p.getCode() == null ? p.getRowNo() + "-" + p.getColNo() : p.getCode();
            }).collect(Collectors.joining("、"));
            throw ApiException.conflict("小区块 " + codes + (busy.size() > 6 ? " 等 " + busy.size() + " 个" : "")
                    + " 已经分配了品种，不能改成道路、保护区或空地。请先在“布置试验”里清掉品种");
        }
    }

    /** 删除布局里去掉的小区块；有品种、重复、数据或对照关系的不能删 */
    private void removePlots(List<Plot> removed) {
        if (removed.isEmpty()) {
            return;
        }
        List<Long> ids = removed.stream().map(Plot::getId).toList();
        Map<Long, Plot> byId = removed.stream().collect(Collectors.toMap(Plot::getId, p -> p));
        List<Planting> plantings = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery().in(Planting::getPlotId, ids));
        Set<Long> busy = new HashSet<>();
        plantings.stream()
                .filter(p -> p.getVarietyId() != null || p.getReplicateNo() != null || Boolean.TRUE.equals(p.getAbnormal()))
                .forEach(p -> busy.add(p.getPlotId()));
        List<Long> plantingIds = plantings.stream().map(Planting::getId).toList();
        if (!plantingIds.isEmpty()) {
            Set<Long> withData = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                            .select(Observation::getPlantingId)
                            .in(Observation::getPlantingId, plantingIds).eq(Observation::getDeleted, false))
                    .stream().map(Observation::getPlantingId).collect(Collectors.toSet());
            plantings.stream().filter(p -> withData.contains(p.getId())).forEach(p -> busy.add(p.getPlotId()));
        }
        linkPlotMapper.selectList(Wrappers.<ControlLinkPlot>lambdaQuery().in(ControlLinkPlot::getPlotId, ids))
                .forEach(lp -> busy.add(lp.getPlotId()));
        if (!busy.isEmpty()) {
            String codes = busy.stream().limit(6).map(id -> {
                Plot p = byId.get(id);
                return p.getCode() == null ? p.getRowNo() + "-" + p.getColNo() : p.getCode();
            }).collect(Collectors.joining("、"));
            throw ApiException.conflict("这样改会去掉小区块 " + codes + (busy.size() > 6 ? " 等 " + busy.size() + " 个" : "")
                    + "，它们已经分配了品种、录了数据或设了对照。请先在“布置试验”里清掉，或者换一种改法");
        }
        if (!plantingIds.isEmpty()) {
            plantingMapper.deleteByIds(plantingIds);
        }
        plotMapper.deleteByIds(ids);
    }

    private static BigDecimal meters(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 检查这些小区块都属于这块实验田，返回这些小区块。 */
    public List<Plot> requireInField(Long fieldId, List<Long> plotIds) {
        List<Plot> found = plotMapper.selectList(Wrappers.<Plot>lambdaQuery()
                .eq(Plot::getFieldId, fieldId)
                .in(Plot::getId, plotIds));
        if (found.size() != new HashSet<>(plotIds).size()) {
            throw ApiException.badRequest("有小区块不属于这块实验田");
        }
        return found;
    }

    /** 每 500 个小区块一条 INSERT，几百上千个小区块也不用一个个发 SQL */
    private void insertPlots(List<Plot> plots) {
        for (int i = 0; i < plots.size(); i += 500) {
            plotMapper.insertAll(plots.subList(i, Math.min(i + 500, plots.size())));
        }
    }

    private static Plot newPlot(Long fieldId, int row, int col, String code, FieldRequest req) {
        Plot plot = new Plot();
        plot.setFieldId(fieldId);
        plot.setRowNo(row);
        plot.setColNo(col);
        plot.setCode(code);
        plot.setKind(Plot.KIND_PLOT);
        plot.setLengthM(req.plotLengthM());
        plot.setWidthM(req.plotWidthM());
        return plot;
    }

    private static void apply(Field field, FieldRequest req) {
        field.setName(req.name());
        field.setLocation(req.location());
        field.setLatitude(req.latitude());
        field.setLongitude(req.longitude());
        field.setAltitudeM(req.altitudeM());
        field.setAreaMu(req.areaMu());
        field.setRowCount(req.rowCount());
        field.setColCount(req.colCount());
        field.setSoilType(req.soilType());
        field.setIrrigated(req.irrigated());
        field.setPreviousCrop(req.previousCrop());
        field.setRemark(req.remark());
    }
}
