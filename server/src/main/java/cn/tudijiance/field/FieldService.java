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

    private static final Set<String> KINDS = Set.of(Plot.KIND_PLOT, Plot.KIND_GUARD, Plot.KIND_PATH);

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
        return fieldMapper.selectList(Wrappers.<Field>lambdaQuery().orderByAsc(Field::getId));
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
        return new FieldDetail(field, plots, saved != null ? saved : LayoutGeometry.legacy(field, plots), saved != null);
    }

    /** 保存过的区块布局；还是按行列建的老网格时为 null */
    public FieldLayout savedLayout(Field field) {
        return field.getLayout() == null || field.getLayout().isBlank()
                ? null : json.readValue(field.getLayout(), FieldLayout.class);
    }

    public List<Plot> plots(Long fieldId) {
        return plotMapper.selectList(Wrappers.<Plot>lambdaQuery()
                .eq(Plot::getFieldId, fieldId)
                .orderByAsc(Plot::getRowNo, Plot::getColNo));
    }

    /** 新建实验田，并按行顺序生成小区，默认编号 1、2、3…… */
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

    /** 修改实验田信息。行列数变小时，删掉网格外的小区；这些小区已经种了品种的话不允许缩小。 */
    @Transactional
    public FieldDetail update(Long id, FieldRequest req) {
        Field field = get(id);
        int oldRows = field.getRowCount();
        int oldCols = field.getColCount();
        apply(field, req);
        field.setUpdatedAt(LocalDateTime.now(clock));
        // 有区块布局的实验田，小区由布局决定，这里不改行列
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
                throw ApiException.conflict("要删除的小区里已经分配了品种，不能缩小网格");
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
        get(id);
        fieldMapper.deleteById(id);
    }

    /**
     * 批量修改编号。先检查修改后整块田里没有重复编号，再分两步更新（先清空再写入），
     * 这样两个小区互换编号时不会撞上唯一约束。
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
                throw ApiException.badRequest("小区 " + plotId + " 不属于这块实验田");
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
            throw ApiException.badRequest("这块地的小区大小由区块布局决定，请在平面图的“编辑地块”里改");
        }
        if (req.kind() != null && !KINDS.contains(req.kind())) {
            throw ApiException.badRequest("小区类型只能是 PLOT、GUARD 或 PATH");
        }
        requireInField(fieldId, req.plotIds());
        if (req.kind() != null && !Plot.KIND_PLOT.equals(req.kind())) {
            long used = plantingMapper.selectCount(Wrappers.<Planting>lambdaQuery()
                    .in(Planting::getPlotId, req.plotIds())
                    .isNotNull(Planting::getVarietyId));
            if (used > 0) {
                throw ApiException.conflict("选中的小区已经分配了品种，请先清除品种再改成保护区或走道");
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
     * 保存区块布局，并按布局更新小区：布局里带着小区 id 的格子更新它的位置和大小，没带的新建小区，
     * 布局里没有了的小区删掉——但已经分配了品种、设了重复、录了数据或设了对照的小区不能删，要先清掉。
     * 新小区按顺序编号（用没被占用的最小数字）。
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
                throw ApiException.badRequest("小区太大了：长和宽不能超过 9999 米");
            }
            if (leaf.plotId() == null) {
                continue;
            }
            if (!byId.containsKey(leaf.plotId())) {
                throw ApiException.conflict("实验田的小区刚刚被别人改过，请刷新后再改布局");
            }
            if (!kept.add(leaf.plotId())) {
                throw ApiException.badRequest("同一个小区在布局里出现了两次");
            }
        }
        List<Plot> removed = existing.stream().filter(p -> !kept.contains(p.getId())).toList();
        removePlots(removed);

        // 先把留下的小区挪到临时位置（负数），再写新位置，免得互换位置时撞上唯一约束
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
            plot.setKind(Plot.KIND_PLOT);
            plot.setLengthM(length);
            plot.setWidthM(width);
            created.add(plot);
        }
        insertPlots(created);

        // 新建的小区按位置找回 id，写进布局
        Map<String, Long> idAt = new HashMap<>();
        plots(fieldId).forEach(p -> idAt.put(p.getRowNo() + "," + p.getColNo(), p.getId()));
        Map<String, Map<String, Long>> idsByBlock = new LinkedHashMap<>();
        for (Leaf leaf : flat.leaves()) {
            idsByBlock.computeIfAbsent(leaf.block().id(), k -> new LinkedHashMap<>())
                    .put(leaf.key(), idAt.get(leaf.rowNo() + "," + leaf.colNo()));
        }
        List<Block> blocks = layout.blocks().stream().map(b -> {
            if (!FieldLayout.PLANT.equals(b.kind())) {
                return new Block(b.id(), b.r(), b.c(), b.name(), b.kind(), b.fit(), null);
            }
            Inner in = b.inner();
            return new Block(b.id(), b.r(), b.c(), b.name(), b.kind(), b.fit(),
                    new Inner(in.rows(), in.cols(), in.merges() == null ? List.of() : in.merges(),
                            idsByBlock.getOrDefault(b.id(), Map.of())));
        }).toList();
        FieldLayout saved = new FieldLayout(1, layout.unit(), layout.rows(), layout.cols(),
                layout.merges() == null ? List.of() : layout.merges(), blocks, layout.nextBlockNo(),
                layout.defaultInner(), layout.northDeg() == null ? 0.0 : layout.northDeg());

        field.setLayout(json.writeValueAsString(saved));
        field.setRowCount(flat.rowCount());
        field.setColCount(flat.colCount());
        field.setUpdatedAt(LocalDateTime.now(clock));
        fieldMapper.updateById(field);
        return detail(fieldId);
    }

    /** 删除布局里去掉的小区；有品种、重复、数据或对照关系的不能删 */
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
            throw ApiException.conflict("这样改会去掉小区 " + codes + (busy.size() > 6 ? " 等 " + busy.size() + " 个" : "")
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

    /** 检查这些小区都属于这块实验田，返回这些小区。 */
    public List<Plot> requireInField(Long fieldId, List<Long> plotIds) {
        List<Plot> found = plotMapper.selectList(Wrappers.<Plot>lambdaQuery()
                .eq(Plot::getFieldId, fieldId)
                .in(Plot::getId, plotIds));
        if (found.size() != new HashSet<>(plotIds).size()) {
            throw ApiException.badRequest("有小区不属于这块实验田");
        }
        return found;
    }

    /** 每 500 个小区一条 INSERT，几百上千个小区也不用一个个发 SQL */
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
