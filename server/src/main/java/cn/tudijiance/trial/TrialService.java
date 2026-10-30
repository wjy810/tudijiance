package cn.tudijiance.trial;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;
import cn.tudijiance.trial.TrialDtos.AssignRequest;
import cn.tudijiance.trial.TrialDtos.Attribute;
import cn.tudijiance.trial.TrialDtos.Layout;
import cn.tudijiance.trial.TrialDtos.LinkPlotView;
import cn.tudijiance.trial.TrialDtos.LinkRequest;
import cn.tudijiance.trial.TrialDtos.LinkView;
import cn.tudijiance.trial.TrialDtos.PlantingView;
import cn.tudijiance.trial.TrialDtos.TrialRequest;
import cn.tudijiance.variety.Variety;
import cn.tudijiance.variety.VarietyMapper;

@Service
public class TrialService {

    private static final Set<String> TRIAL_TYPES = Set.of("REGIONAL", "PRODUCTION", "COMPARISON", "OBSERVATION");
    private static final Set<String> ZONES = Set.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9", "Q10", "Q11");
    private static final String DEFAULT_LINK_LABEL = "对照品种";

    private final TrialMapper trialMapper;
    private final PlantingMapper plantingMapper;
    private final ControlLinkMapper linkMapper;
    private final ControlLinkPlotMapper linkPlotMapper;
    private final VarietyMapper varietyMapper;
    private final FieldService fieldService;
    private final Clock clock;

    public TrialService(TrialMapper trialMapper, PlantingMapper plantingMapper, ControlLinkMapper linkMapper,
                        ControlLinkPlotMapper linkPlotMapper, VarietyMapper varietyMapper,
                        FieldService fieldService, Clock clock) {
        this.trialMapper = trialMapper;
        this.plantingMapper = plantingMapper;
        this.linkMapper = linkMapper;
        this.linkPlotMapper = linkPlotMapper;
        this.varietyMapper = varietyMapper;
        this.fieldService = fieldService;
        this.clock = clock;
    }

    public List<Trial> list(Long fieldId) {
        var query = Wrappers.<Trial>lambdaQuery().orderByDesc(Trial::getSeasonYear).orderByAsc(Trial::getId);
        if (fieldId != null) {
            query.eq(Trial::getFieldId, fieldId);
        }
        return trialMapper.selectList(query);
    }

    public Trial get(Long id) {
        Trial trial = trialMapper.selectById(id);
        if (trial == null) {
            throw ApiException.notFound("试验");
        }
        return trial;
    }

    public Trial create(TrialRequest req) {
        fieldService.get(req.fieldId());
        Trial trial = new Trial();
        apply(trial, req);
        trial.setCreatedAt(LocalDateTime.now(clock));
        trialMapper.insert(trial);
        return trial;
    }

    public Trial update(Long id, TrialRequest req) {
        Trial trial = get(id);
        if (!trial.getFieldId().equals(req.fieldId())) {
            throw ApiException.badRequest("试验建好后不能更换实验田");
        }
        apply(trial, req);
        trialMapper.updateById(trial);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        trialMapper.deleteById(id);
    }

    public Layout layout(Long trialId) {
        Trial trial = get(trialId);
        List<PlantingView> plantings = plantingMapper.selectList(
                        Wrappers.<Planting>lambdaQuery().eq(Planting::getTrialId, trialId))
                .stream()
                .map(p -> new PlantingView(p.getPlotId(), p.getVarietyId(), p.getReplicateNo()))
                .toList();
        List<ControlLink> links = linkMapper.selectList(
                Wrappers.<ControlLink>lambdaQuery().eq(ControlLink::getTrialId, trialId).orderByAsc(ControlLink::getId));
        List<LinkView> linkViews = links.isEmpty() ? List.of() : linkViews(links);
        return new Layout(trial, plantings, linkViews);
    }

    /** 给选中的小区批量设置品种或重复号。小区还没有种植记录时自动创建。 */
    @Transactional
    public Layout assign(Long trialId, AssignRequest req) {
        Trial trial = get(trialId);
        List<Plot> plots = fieldService.requireInField(trial.getFieldId(), req.plotIds());
        if (req.attribute() == Attribute.VARIETY && req.value() != null) {
            if (varietyMapper.selectById(req.value()) == null) {
                throw ApiException.notFound("品种");
            }
            boolean nonPlot = plots.stream().anyMatch(p -> !Plot.KIND_PLOT.equals(p.getKind()));
            if (nonPlot) {
                throw ApiException.badRequest("保护区和走道不能分配品种");
            }
        }
        if (req.attribute() == Attribute.REPLICATE && req.value() != null
                && (req.value() < 1 || req.value() > 20)) {
            throw ApiException.badRequest("重复号要在 1 到 20 之间");
        }

        Map<Long, Planting> existing = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                        .eq(Planting::getTrialId, trialId)
                        .in(Planting::getPlotId, req.plotIds()))
                .stream().collect(Collectors.toMap(Planting::getPlotId, Function.identity()));
        for (Long plotId : new HashSet<>(req.plotIds())) {
            if (!existing.containsKey(plotId)) {
                Planting planting = new Planting();
                planting.setTrialId(trialId);
                planting.setPlotId(plotId);
                plantingMapper.insert(planting);
            }
        }

        var update = Wrappers.<Planting>lambdaUpdate()
                .eq(Planting::getTrialId, trialId)
                .in(Planting::getPlotId, req.plotIds());
        if (req.attribute() == Attribute.VARIETY) {
            update.set(Planting::getVarietyId, req.value());
        } else {
            update.set(Planting::getReplicateNo, req.value() == null ? null : req.value().intValue());
        }
        plantingMapper.update(null, update);
        return layout(trialId);
    }

    @Transactional
    public Layout createLink(Long trialId, LinkRequest req) {
        Trial trial = get(trialId);
        Set<Long> plotIds = new HashSet<>(req.plotIds());
        if (plotIds.size() < 2) {
            throw ApiException.badRequest("对照关系至少要选 2 个小区");
        }
        fieldService.requireInField(trial.getFieldId(), List.copyOf(plotIds));

        Set<Long> controls = req.controlPlotIds() == null ? new HashSet<>() : new HashSet<>(req.controlPlotIds());
        if (!plotIds.containsAll(controls)) {
            throw ApiException.badRequest("对照小区必须在选中的小区里");
        }
        if (controls.isEmpty()) {
            controls = plotsWithControlVariety(trialId, plotIds);
        }
        if (controls.isEmpty()) {
            throw ApiException.badRequest("选中的小区里没有种对照品种，请指定哪个小区是对照");
        }
        if (controls.size() == plotIds.size()) {
            throw ApiException.badRequest("至少要有一个小区不是对照");
        }

        ControlLink link = new ControlLink();
        link.setTrialId(trialId);
        link.setLabel(req.label() == null || req.label().isBlank() ? DEFAULT_LINK_LABEL : req.label().trim());
        link.setCreatedAt(LocalDateTime.now(clock));
        linkMapper.insert(link);
        for (Long plotId : plotIds) {
            ControlLinkPlot lp = new ControlLinkPlot();
            lp.setLinkId(link.getId());
            lp.setPlotId(plotId);
            lp.setIsControl(controls.contains(plotId));
            linkPlotMapper.insert(lp);
        }
        return layout(trialId);
    }

    @Transactional
    public Layout deleteLink(Long trialId, Long linkId) {
        ControlLink link = linkMapper.selectById(linkId);
        if (link == null || !link.getTrialId().equals(trialId)) {
            throw ApiException.notFound("对照关系");
        }
        linkMapper.deleteById(linkId);
        return layout(trialId);
    }

    private Set<Long> plotsWithControlVariety(Long trialId, Set<Long> plotIds) {
        List<Planting> plantings = plantingMapper.selectList(Wrappers.<Planting>lambdaQuery()
                .eq(Planting::getTrialId, trialId)
                .in(Planting::getPlotId, plotIds)
                .isNotNull(Planting::getVarietyId));
        if (plantings.isEmpty()) {
            return new HashSet<>();
        }
        Set<Long> varietyIds = plantings.stream().map(Planting::getVarietyId).collect(Collectors.toSet());
        Set<Long> controlVarieties = varietyMapper.selectList(Wrappers.<Variety>lambdaQuery()
                        .in(Variety::getId, varietyIds)
                        .isNotNull(Variety::getControlType))
                .stream().map(Variety::getId).collect(Collectors.toSet());
        return plantings.stream()
                .filter(p -> controlVarieties.contains(p.getVarietyId()))
                .map(Planting::getPlotId)
                .collect(Collectors.toSet());
    }

    private List<LinkView> linkViews(List<ControlLink> links) {
        Map<Long, List<ControlLinkPlot>> byLink = linkPlotMapper.selectList(Wrappers.<ControlLinkPlot>lambdaQuery()
                        .in(ControlLinkPlot::getLinkId, links.stream().map(ControlLink::getId).toList()))
                .stream().collect(Collectors.groupingBy(ControlLinkPlot::getLinkId));
        return links.stream()
                .map(l -> new LinkView(l.getId(), l.getLabel(), byLink.getOrDefault(l.getId(), List.of()).stream()
                        .map(lp -> new LinkPlotView(lp.getPlotId(), Boolean.TRUE.equals(lp.getIsControl())))
                        .toList()))
                .toList();
    }

    private static void apply(Trial trial, TrialRequest req) {
        if (!TRIAL_TYPES.contains(req.trialType())) {
            throw ApiException.badRequest("试验类型的取值不正确：" + req.trialType());
        }
        String zone = req.wheatZone() == null || req.wheatZone().isBlank() ? null : req.wheatZone();
        if (zone != null && !ZONES.contains(zone)) {
            throw ApiException.badRequest("麦区只能是 Q1 到 Q11");
        }
        trial.setFieldId(req.fieldId());
        trial.setSeasonYear(req.seasonYear());
        trial.setName(req.name().trim());
        trial.setTrialType(req.trialType());
        trial.setWheatZone(zone);
        trial.setReplicateCount(req.replicateCount());
        trial.setRemark(req.remark());
    }
}
