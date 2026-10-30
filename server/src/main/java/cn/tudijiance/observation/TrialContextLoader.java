package cn.tudijiance.observation;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.trial.TrialService;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

@Component
public class TrialContextLoader {

    private final TrialService trialService;
    private final FieldService fieldService;
    private final PlantingMapper plantingMapper;
    private final VarietyMapper varietyMapper;

    public TrialContextLoader(TrialService trialService, FieldService fieldService,
                              PlantingMapper plantingMapper, VarietyMapper varietyMapper) {
        this.trialService = trialService;
        this.fieldService = fieldService;
        this.plantingMapper = plantingMapper;
        this.varietyMapper = varietyMapper;
    }

    public TrialContext load(Long trialId) {
        Trial trial = trialService.get(trialId);
        Map<Long, Plot> plots = fieldService.plots(trial.getFieldId()).stream()
                .collect(Collectors.toMap(Plot::getId, Function.identity()));
        Map<Long, Planting> byId = plantingMapper.selectList(
                        Wrappers.<Planting>lambdaQuery().eq(Planting::getTrialId, trialId))
                .stream().collect(Collectors.toMap(Planting::getId, Function.identity()));
        Map<Long, Planting> byPlot = byId.values().stream()
                .collect(Collectors.toMap(Planting::getPlotId, Function.identity()));
        Map<Long, Variety> varieties = varietyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Variety::getId, Function.identity()));
        return new TrialContext(trial, plots, byId, byPlot, varieties);
    }
}
