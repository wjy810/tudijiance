package cn.tudijiance.importing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.TrialContext;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.variety.Variety;

/** 找表格里一条记录对应的小区：按小区编号，或者按这一年布局里的"品种 + 重复"。查找表在构造时一次建好。 */
final class PlotLocator {

    private final Map<String, Plot> byCode = new HashMap<>();
    private final Map<String, List<Planting>> byVariety = new HashMap<>();

    PlotLocator(TrialContext ctx) {
        for (Plot p : ctx.plots().values()) {
            if (p.getCode() != null) {
                byCode.putIfAbsent(p.getCode(), p);
            }
        }
        for (Planting p : ctx.plantingsById().values()) {
            Variety v = ctx.varietyOf(p);
            if (v != null) {
                byVariety.computeIfAbsent(v.getName().strip(), k -> new ArrayList<>()).add(p);
            }
        }
    }

    /** 编号对应的小区，没有时返回 null */
    Plot byCode(String code) {
        return byCode.get(code);
    }

    /** 这一年种了这个品种的种植记录；replicate 不为 null 时只要这个重复的 */
    List<Planting> byVariety(String varietyName, Integer replicate) {
        return byVariety.getOrDefault(varietyName, List.of()).stream()
                .filter(p -> replicate == null || replicate.equals(p.getReplicateNo()))
                .toList();
    }
}
