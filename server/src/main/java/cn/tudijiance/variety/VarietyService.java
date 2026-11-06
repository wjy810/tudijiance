package cn.tudijiance.variety;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.variety.VarietyController.VarietyRequest;

/** 品种：名称、编号、选育单位、冬春性、品质类型、对照类型。 */
@Service
public class VarietyService {

    private static final Set<String> ECOTYPES = Set.of("SPRING", "WEAK_SPRING", "SEMI_WINTER", "WINTER");
    private static final Set<String> QUALITY_TYPES = Set.of("STRONG", "MEDIUM_STRONG", "MEDIUM", "WEAK");
    private static final Set<String> CONTROL_TYPES = Set.of("YIELD", "QUALITY", "RESISTANCE");

    private final VarietyMapper varietyMapper;
    private final PlantingMapper plantingMapper;
    private final Clock clock;

    public VarietyService(VarietyMapper varietyMapper, PlantingMapper plantingMapper, Clock clock) {
        this.varietyMapper = varietyMapper;
        this.plantingMapper = plantingMapper;
        this.clock = clock;
    }

    public List<Variety> list(String keyword) {
        var query = Wrappers.<Variety>lambdaQuery().orderByAsc(Variety::getName);
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(Variety::getName, keyword).or().like(Variety::getCode, keyword));
        }
        return varietyMapper.selectList(query);
    }

    public Variety get(Long id) {
        Variety variety = varietyMapper.selectById(id);
        if (variety == null) {
            throw ApiException.notFound("品种");
        }
        return variety;
    }

    public Variety create(VarietyRequest req) {
        Variety variety = new Variety();
        apply(variety, req);
        requireUniqueName(variety.getName(), null);
        variety.setCreatedAt(LocalDateTime.now(clock));
        varietyMapper.insert(variety);
        return variety;
    }

    public Variety update(Long id, VarietyRequest req) {
        Variety variety = get(id);
        String oldName = variety.getName();
        apply(variety, req);
        requireUniqueName(variety.getName(), id);
        // 也是育种的原始材料时：名称不能有"/""*"，旧名记成曾用名
        if (variety.isLine() && !variety.getName().equals(oldName)) {
            String problem = cn.tudijiance.breeding.Pedigree.nameProblem(variety.getName());
            if (problem != null) {
                throw ApiException.badRequest(problem);
            }
            varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                    .set(Variety::getFormerNames, cn.tudijiance.breeding.LineService.appendFormer(
                            variety.getFormerNames(), oldName, variety.getLineCode()))
                    .eq(Variety::getId, id));
        }
        // 用 update 而不是 updateById：清空的字段也要写回数据库
        varietyMapper.update(null, Wrappers.<Variety>lambdaUpdate()
                .set(Variety::getName, variety.getName())
                .set(Variety::getCode, variety.getCode())
                .set(Variety::getBreeder, variety.getBreeder())
                .set(Variety::getEcotype, variety.getEcotype())
                .set(Variety::getQualityType, variety.getQualityType())
                .set(Variety::getControlType, variety.getControlType())
                .set(Variety::getRemark, variety.getRemark())
                .eq(Variety::getId, id));
        return get(id);
    }

    /** 已经种在小区里的品种不能删除，免得历年数据对不上品种 */
    public void delete(Long id) {
        Variety variety = get(id);
        long children = varietyMapper.selectCount(Wrappers.<Variety>lambdaQuery()
                .eq(Variety::getMotherId, id).or().eq(Variety::getFatherId, id).or().eq(Variety::getParentId, id));
        if (children > 0) {
            throw ApiException.conflict("\"" + variety.getName() + "\"在育种里有 " + children + " 个子代，不能删除");
        }
        long used = plantingMapper.selectCount(Wrappers.<Planting>lambdaQuery().eq(Planting::getVarietyId, id));
        if (used > 0) {
            throw ApiException.conflict("\"" + variety.getName() + "\"已经种在 " + used + " 个小区里，不能删除");
        }
        varietyMapper.deleteById(id);
    }

    private void requireUniqueName(String name, Long exceptId) {
        var query = Wrappers.<Variety>lambdaQuery().eq(Variety::getName, name);
        if (exceptId != null) {
            query.ne(Variety::getId, exceptId);
        }
        if (varietyMapper.selectCount(query) > 0) {
            throw ApiException.conflict("已经有叫\"" + name + "\"的品种");
        }
    }

    private static void apply(Variety v, VarietyRequest req) {
        check(req.ecotype(), ECOTYPES, "冬春性");
        check(req.qualityType(), QUALITY_TYPES, "品质类型");
        check(req.controlType(), CONTROL_TYPES, "对照类型");
        v.setName(req.name().trim());
        v.setCode(blankToNull(req.code()));
        v.setBreeder(blankToNull(req.breeder()));
        v.setEcotype(blankToNull(req.ecotype()));
        v.setQualityType(blankToNull(req.qualityType()));
        v.setControlType(blankToNull(req.controlType()));
        v.setRemark(blankToNull(req.remark()));
    }

    private static void check(String value, Set<String> allowed, String what) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            throw ApiException.badRequest(what + "的取值不正确：" + value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
