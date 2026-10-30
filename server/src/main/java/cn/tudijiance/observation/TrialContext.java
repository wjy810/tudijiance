package cn.tudijiance.observation;

import java.util.Map;

import cn.tudijiance.field.Plot;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.Trial;
import cn.tudijiance.variety.Variety;

/** 一个试验的小区、种植记录和品种，查询时一次性取出，避免逐条查库。 */
public record TrialContext(
        Trial trial,
        Map<Long, Plot> plots,
        Map<Long, Planting> plantingsById,
        Map<Long, Planting> plantingsByPlot,
        Map<Long, Variety> varieties) {

    public Variety varietyOf(Planting planting) {
        return planting == null || planting.getVarietyId() == null ? null : varieties.get(planting.getVarietyId());
    }
}
