package cn.tudijiance.trait;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;

/** 读取性状定义和选项。数据量很小（一两百条），每次请求直接查库。 */
@Component
public class TraitCatalog {

    private final TraitDefMapper traitMapper;
    private final TraitOptionMapper optionMapper;

    public TraitCatalog(TraitDefMapper traitMapper, TraitOptionMapper optionMapper) {
        this.traitMapper = traitMapper;
        this.optionMapper = optionMapper;
    }

    public TraitDef get(Long id) {
        TraitDef trait = traitMapper.selectById(id);
        if (trait == null) {
            throw ApiException.notFound("录入项目");
        }
        return trait;
    }

    public TraitDef byCode(String code) {
        return traitMapper.selectOne(Wrappers.<TraitDef>lambdaQuery().eq(TraitDef::getCode, code));
    }

    public Map<Long, TraitDef> all() {
        return traitMapper.selectList(null).stream().collect(Collectors.toMap(TraitDef::getId, Function.identity()));
    }

    public List<TraitOption> optionsOf(Long traitId) {
        return optionMapper.selectList(Wrappers.<TraitOption>lambdaQuery()
                .eq(TraitOption::getTraitId, traitId).orderByAsc(TraitOption::getSortOrder));
    }

    public Map<Long, List<TraitOption>> allOptions() {
        return optionMapper.selectList(Wrappers.<TraitOption>lambdaQuery().orderByAsc(TraitOption::getSortOrder))
                .stream().collect(Collectors.groupingBy(TraitOption::getTraitId));
    }
}
