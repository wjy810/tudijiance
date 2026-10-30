package cn.tudijiance.field;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.FieldDtos.FieldDetail;
import cn.tudijiance.field.FieldDtos.FieldRequest;
import cn.tudijiance.field.FieldDtos.PlotPatchRequest;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;

@Service
public class FieldService {

    private static final Set<String> KINDS = Set.of(Plot.KIND_PLOT, Plot.KIND_GUARD, Plot.KIND_PATH);

    private final FieldMapper fieldMapper;
    private final PlotMapper plotMapper;
    private final PlantingMapper plantingMapper;
    private final Clock clock;

    public FieldService(FieldMapper fieldMapper, PlotMapper plotMapper, PlantingMapper plantingMapper, Clock clock) {
        this.fieldMapper = fieldMapper;
        this.plotMapper = plotMapper;
        this.plantingMapper = plantingMapper;
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
        return new FieldDetail(get(id), plots(id));
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
        int seq = 1;
        for (int r = 1; r <= req.rowCount(); r++) {
            for (int c = 1; c <= req.colCount(); c++) {
                insertPlot(field.getId(), r, c, String.valueOf(seq++));
            }
        }
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
        for (int r = 1; r <= req.rowCount(); r++) {
            for (int c = 1; c <= req.colCount(); c++) {
                if (r > oldRows || c > oldCols) {
                    insertPlot(id, r, c, null);
                }
            }
        }
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
        get(fieldId);
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

    private void insertPlot(Long fieldId, int row, int col, String code) {
        Plot plot = new Plot();
        plot.setFieldId(fieldId);
        plot.setRowNo(row);
        plot.setColNo(col);
        plot.setCode(code);
        plot.setKind(Plot.KIND_PLOT);
        plotMapper.insert(plot);
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
