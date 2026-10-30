package cn.tudijiance.variety;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/varieties")
public class VarietyController {

    private static final Set<String> ECOTYPES = Set.of("SPRING", "WEAK_SPRING", "SEMI_WINTER", "WINTER");
    private static final Set<String> QUALITY_TYPES = Set.of("STRONG", "MEDIUM_STRONG", "MEDIUM", "WEAK");
    private static final Set<String> CONTROL_TYPES = Set.of("YIELD", "QUALITY", "RESISTANCE");

    private final VarietyMapper varietyMapper;
    private final Clock clock;

    public VarietyController(VarietyMapper varietyMapper, Clock clock) {
        this.varietyMapper = varietyMapper;
        this.clock = clock;
    }

    @GetMapping
    public List<Variety> list(@RequestParam(required = false) String keyword) {
        var query = Wrappers.<Variety>lambdaQuery().orderByAsc(Variety::getName);
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(Variety::getName, keyword).or().like(Variety::getCode, keyword));
        }
        return varietyMapper.selectList(query);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Variety create(@Valid @RequestBody VarietyRequest req) {
        Variety variety = new Variety();
        apply(variety, req);
        variety.setCreatedAt(LocalDateTime.now(clock));
        varietyMapper.insert(variety);
        return variety;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Variety update(@PathVariable Long id, @Valid @RequestBody VarietyRequest req) {
        Variety variety = get(id);
        apply(variety, req);
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        get(id);
        varietyMapper.deleteById(id);
    }

    private Variety get(Long id) {
        Variety variety = varietyMapper.selectById(id);
        if (variety == null) {
            throw ApiException.notFound("品种");
        }
        return variety;
    }

    private static void apply(Variety v, VarietyRequest req) {
        check(req.ecotype(), ECOTYPES, "生态型");
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

    public record VarietyRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String code,
            @Size(max = 200) String breeder,
            String ecotype,
            String qualityType,
            String controlType,
            @Size(max = 500) String remark) {
    }
}
