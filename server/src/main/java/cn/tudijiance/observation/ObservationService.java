package cn.tudijiance.observation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Plot;
import cn.tudijiance.observation.ObservationDtos.AbnormalRequest;
import cn.tudijiance.observation.ObservationDtos.EntryItem;
import cn.tudijiance.observation.ObservationDtos.EntryRequest;
import cn.tudijiance.observation.ObservationDtos.HistoryView;
import cn.tudijiance.observation.ObservationDtos.ObservationFilter;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.observation.ObservationDtos.PageResult;
import cn.tudijiance.observation.ObservationDtos.UpdateRequest;
import cn.tudijiance.trait.TraitCatalog;
import cn.tudijiance.trait.TraitDef;
import cn.tudijiance.trait.TraitOption;
import cn.tudijiance.trial.Planting;
import cn.tudijiance.trial.PlantingMapper;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserMapper;
import cn.tudijiance.variety.Variety;

@Service
public class ObservationService {

    private static final String ENTITY = "OBSERVATION";
    private static final int MAX_PAGE_SIZE = 500;
    private static final int EXPORT_LIMIT = 100_000;

    private final ObservationMapper observationMapper;
    private final AuditLogMapper auditMapper;
    private final PlantingMapper plantingMapper;
    private final UserMapper userMapper;
    private final TraitCatalog catalog;
    private final TrialContextLoader contextLoader;
    private final Clock clock;

    public ObservationService(ObservationMapper observationMapper, AuditLogMapper auditMapper,
                              PlantingMapper plantingMapper, UserMapper userMapper, TraitCatalog catalog,
                              TrialContextLoader contextLoader, Clock clock) {
        this.observationMapper = observationMapper;
        this.auditMapper = auditMapper;
        this.plantingMapper = plantingMapper;
        this.userMapper = userMapper;
        this.catalog = catalog;
        this.contextLoader = contextLoader;
        this.clock = clock;
    }

    /** 给一个或多个小区录入同一个项目。重复提交（同一个 clientUuid）不会重复保存。 */
    @Transactional
    public List<ObservationView> create(EntryRequest req, long userId) {
        TrialContext ctx = contextLoader.load(req.trialId());
        TraitDef trait = catalog.get(req.traitId());
        if (!Boolean.TRUE.equals(trait.getEnabled())) {
            throw ApiException.badRequest(trait.getName() + "已停用");
        }
        if (Boolean.TRUE.equals(trait.getIsComputed())) {
            throw ApiException.badRequest(trait.getName() + "由系统自动计算，不用录入");
        }
        List<TraitOption> options = catalog.optionsOf(trait.getId());
        TraitValues.Value value = TraitValues.validate(trait, options, req.valueNum(), req.valueText(), req.valueDate());
        LocalDate observedOn = req.observedOn() != null ? req.observedOn() : LocalDate.now(clock);
        if (observedOn.isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("日期不能晚于今天");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> savedIds = new ArrayList<>();
        Set<Long> seenPlots = new HashSet<>();
        // 手机离线后重复上传：已经存过的客户端编号直接返回原来那条（一次查出来，不逐条查）
        Map<String, Observation> existingByUuid = observationMapper.selectList(Wrappers.<Observation>lambdaQuery()
                        .in(Observation::getClientUuid, req.items().stream().map(EntryItem::clientUuid).toList()))
                .stream().collect(Collectors.toMap(Observation::getClientUuid, o -> o));
        for (EntryItem item : req.items()) {
            if (!seenPlots.add(item.plotId())) {
                continue;
            }
            Plot plot = ctx.plots().get(item.plotId());
            if (plot == null) {
                throw ApiException.badRequest("小区 " + item.plotId() + " 不在这个试验的实验田里");
            }
            if (!Plot.KIND_PLOT.equals(plot.getKind())) {
                throw ApiException.badRequest("小区 " + plot.getCode() + " 是保护区或走道，不能录入");
            }
            Observation existing = existingByUuid.get(item.clientUuid());
            if (existing != null) {
                savedIds.add(existing.getId());
                continue;
            }
            Planting planting = plantingFor(ctx, plot.getId());
            Observation o = new Observation();
            o.setClientUuid(item.clientUuid());
            o.setPlantingId(planting.getId());
            o.setTraitId(trait.getId());
            o.setObservedOn(observedOn);
            o.setValueNum(value.num());
            o.setValueText(value.text());
            o.setValueDate(value.date());
            o.setNote(blankToNull(req.note()));
            o.setSource(Observation.SOURCE_MANUAL);
            o.setRecordedBy(userId);
            o.setRecordedAt(now);
            o.setVersion(1);
            o.setDeleted(false);
            observationMapper.insert(o);
            audit(o.getId(), "CREATE", null, TraitValues.display(trait, options, value.num(), value.text(), value.date()),
                    userId, now);
            savedIds.add(o.getId());
        }
        return views(observationMapper.selectByIds(savedIds), ctx);
    }

    /** 查询一个试验的数据，可以按品种、小区、项目、类别、日期、录入人筛选。page 从 1 开始。 */
    public PageResult<ObservationView> list(ObservationFilter filter, int page, int size) {
        TrialContext ctx = contextLoader.load(filter.trialId());
        var query = query(filter, ctx);
        if (query == null) {
            return new PageResult<>(0, List.of());
        }
        long total = observationMapper.selectCount(query);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (Math.max(page, 1) - 1) * pageSize;
        query.orderByDesc(Observation::getObservedOn).orderByDesc(Observation::getId)
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        return new PageResult<>(total, views(observationMapper.selectList(query), ctx));
    }

    /** 导出用：符合条件的全部数据（最多 EXPORT_LIMIT 条），按小区、项目、日期排好。 */
    public List<ObservationView> listForExport(ObservationFilter filter) {
        TrialContext ctx = contextLoader.load(filter.trialId());
        var query = query(filter, ctx);
        if (query == null) {
            return List.of();
        }
        query.orderByAsc(Observation::getObservedOn).orderByAsc(Observation::getId).last("LIMIT " + EXPORT_LIMIT);
        return views(observationMapper.selectList(query), ctx);
    }

    /** 按筛选条件拼查询；条件下不可能有数据时返回 null。 */
    private LambdaQueryWrapper<Observation> query(ObservationFilter f, TrialContext ctx) {
        List<Long> plantingIds = ctx.plantingsById().values().stream()
                .filter(p -> f.varietyId() == null || f.varietyId().equals(p.getVarietyId()))
                .filter(p -> f.plotId() == null || f.plotId().equals(p.getPlotId()))
                .map(Planting::getId).toList();
        if (plantingIds.isEmpty()) {
            return null;
        }
        var query = Wrappers.<Observation>lambdaQuery()
                .in(Observation::getPlantingId, plantingIds)
                .eq(Observation::getDeleted, false)
                .eq(f.traitId() != null, Observation::getTraitId, f.traitId())
                .ge(f.from() != null, Observation::getObservedOn, f.from())
                .le(f.to() != null, Observation::getObservedOn, f.to())
                .eq(f.recordedBy() != null, Observation::getRecordedBy, f.recordedBy());
        if (f.category() != null && !f.category().isBlank()) {
            List<Long> traitIds = catalog.all().values().stream()
                    .filter(t -> f.category().equals(t.getCategory())).map(TraitDef::getId).toList();
            if (traitIds.isEmpty()) {
                return null;
            }
            query.in(Observation::getTraitId, traitIds);
        }
        return query;
    }

    // ---------- 导入历史表格用 ----------

    /** 检查一个值是否合法，不合法时抛出带提示的 ApiException。 */
    public void checkValue(TraitDef trait, List<TraitOption> options, BigDecimal num, String text, LocalDate date) {
        TraitValues.validate(trait, options, num, text, date);
    }

    public boolean exists(String clientUuid) {
        return observationMapper.selectCount(Wrappers.<Observation>lambdaQuery()
                .eq(Observation::getClientUuid, clientUuid)) > 0;
    }

    /**
     * 导入一个值，来源记为 IMPORT。clientUuid 由导入内容算出，同一份表格再导一次时已存在，返回 false，不重复导入。
     */
    public boolean importValue(TrialContext ctx, Long plotId, TraitDef trait, List<TraitOption> options,
                               BigDecimal num, String text, LocalDate date, LocalDate observedOn,
                               String clientUuid, long userId, LocalDateTime now) {
        TraitValues.Value value = TraitValues.validate(trait, options, num, text, date);
        if (exists(clientUuid)) {
            return false;
        }
        Planting planting = plantingFor(ctx, plotId);
        Observation o = new Observation();
        o.setClientUuid(clientUuid);
        o.setPlantingId(planting.getId());
        o.setTraitId(trait.getId());
        o.setObservedOn(observedOn);
        o.setValueNum(value.num());
        o.setValueText(value.text());
        o.setValueDate(value.date());
        o.setSource(Observation.SOURCE_IMPORT);
        o.setRecordedBy(userId);
        o.setRecordedAt(now);
        o.setVersion(1);
        o.setDeleted(false);
        observationMapper.insert(o);
        audit(o.getId(), "CREATE", null, "导入 " + TraitValues.display(trait, options, value.num(), value.text(),
                value.date()), userId, now);
        return true;
    }

    /** 老师修改一条数据。 */
    @Transactional
    public ObservationView update(Long id, UpdateRequest req, long userId) {
        Observation o = get(id);
        if (!o.getVersion().equals(req.version())) {
            throw new ApiException(HttpStatus.CONFLICT, "这条数据刚被别人改过，请刷新后再改");
        }
        TraitDef trait = catalog.get(o.getTraitId());
        List<TraitOption> options = catalog.optionsOf(trait.getId());
        TraitValues.Value value = TraitValues.validate(trait, options, req.valueNum(), req.valueText(), req.valueDate());
        String before = describe(trait, options, o);
        if (req.observedOn() != null) {
            if (req.observedOn().isAfter(LocalDate.now(clock))) {
                throw ApiException.badRequest("日期不能晚于今天");
            }
            o.setObservedOn(req.observedOn());
        }
        o.setValueNum(value.num());
        o.setValueText(value.text());
        o.setValueDate(value.date());
        o.setNote(blankToNull(req.note()));
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = observationMapper.update(null, Wrappers.<Observation>lambdaUpdate()
                .set(Observation::getObservedOn, o.getObservedOn())
                .set(Observation::getValueNum, o.getValueNum())
                .set(Observation::getValueText, o.getValueText())
                .set(Observation::getValueDate, o.getValueDate())
                .set(Observation::getNote, o.getNote())
                .set(Observation::getUpdatedBy, userId)
                .set(Observation::getUpdatedAt, now)
                .set(Observation::getVersion, o.getVersion() + 1)
                .eq(Observation::getId, id)
                .eq(Observation::getVersion, req.version()));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "这条数据刚被别人改过，请刷新后再改");
        }
        Observation saved = get(id);
        audit(id, "UPDATE", before, describe(trait, options, saved), userId, now);
        return views(List.of(saved), contextLoader.load(trialIdOf(saved))).getFirst();
    }

    /** 老师删除一条数据（只做标记，保留记录）。 */
    @Transactional
    public void delete(Long id, long userId) {
        Observation o = get(id);
        TraitDef trait = catalog.get(o.getTraitId());
        observationMapper.update(null, Wrappers.<Observation>lambdaUpdate()
                .set(Observation::getDeleted, true)
                .set(Observation::getUpdatedBy, userId)
                .set(Observation::getUpdatedAt, LocalDateTime.now(clock))
                .eq(Observation::getId, id));
        audit(id, "DELETE", describe(trait, catalog.optionsOf(trait.getId()), o), null, userId,
                LocalDateTime.now(clock));
    }

    public List<HistoryView> history(Long id) {
        get(id);
        Map<Long, String> users = userNames();
        return auditMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getEntity, ENTITY).eq(AuditLog::getEntityId, id)
                        .orderByDesc(AuditLog::getId))
                .stream()
                .map(a -> new HistoryView(a.getAction(), a.getOldValue(), a.getNewValue(),
                        users.getOrDefault(a.getUserId(), "?"), a.getCreatedAt()))
                .toList();
    }

    /** 平面图右键"标记异常"或取消。 */
    @Transactional
    public void setAbnormal(Long trialId, AbnormalRequest req) {
        TrialContext ctx = contextLoader.load(trialId);
        for (Long plotId : new HashSet<>(req.plotIds())) {
            Plot plot = ctx.plots().get(plotId);
            if (plot == null || !Plot.KIND_PLOT.equals(plot.getKind())) {
                throw ApiException.badRequest("只能给试验小区标记异常");
            }
            Planting planting = plantingFor(ctx, plotId);
            plantingMapper.update(null, Wrappers.<Planting>lambdaUpdate()
                    .set(Planting::getAbnormal, req.abnormal())
                    .set(Planting::getAbnormalNote, req.abnormal() ? blankToNull(req.note()) : null)
                    .eq(Planting::getId, planting.getId()));
        }
    }

    public List<ObservationView> views(List<Observation> list, TrialContext ctx) {
        Map<Long, TraitDef> traits = catalog.all();
        Map<Long, List<TraitOption>> options = catalog.allOptions();
        Map<Long, String> users = userNames();
        return list.stream().map(o -> {
            Planting planting = ctx.plantingsById().get(o.getPlantingId());
            Plot plot = planting == null ? null : ctx.plots().get(planting.getPlotId());
            Variety variety = ctx.varietyOf(planting);
            TraitDef t = traits.get(o.getTraitId());
            List<TraitOption> opts = options.getOrDefault(o.getTraitId(), List.of());
            return new ObservationView(o.getId(), ctx.trial().getId(),
                    plot == null ? null : plot.getId(), plot == null ? null : plot.getCode(),
                    variety == null ? null : variety.getId(), variety == null ? null : variety.getName(),
                    planting == null ? null : planting.getReplicateNo(),
                    t.getId(), t.getName(), t.getCategory(), t.getDataType(), t.getUnit(),
                    Boolean.TRUE.equals(t.getIsStandard()), o.getObservedOn(),
                    o.getValueNum(), o.getValueText(), o.getValueDate(),
                    TraitValues.display(t, opts, o.getValueNum(), o.getValueText(), o.getValueDate()),
                    o.getNote(), o.getSource(),
                    users.get(o.getRecordedBy()), o.getRecordedAt(),
                    o.getUpdatedBy() == null ? null : users.get(o.getUpdatedBy()), o.getUpdatedAt(), o.getVersion());
        }).toList();
    }

    private Planting plantingFor(TrialContext ctx, Long plotId) {
        Planting planting = ctx.plantingsByPlot().get(plotId);
        if (planting == null) {
            planting = new Planting();
            planting.setTrialId(ctx.trial().getId());
            planting.setPlotId(plotId);
            planting.setAbnormal(false);
            plantingMapper.insert(planting);
            ctx.plantingsByPlot().put(plotId, planting);
            ctx.plantingsById().put(planting.getId(), planting);
        }
        return planting;
    }

    private Observation get(Long id) {
        Observation o = observationMapper.selectById(id);
        if (o == null || Boolean.TRUE.equals(o.getDeleted())) {
            throw ApiException.notFound("数据");
        }
        return o;
    }

    private Long trialIdOf(Observation o) {
        return plantingMapper.selectById(o.getPlantingId()).getTrialId();
    }

    private static String describe(TraitDef trait, List<TraitOption> options, Observation o) {
        String value = TraitValues.display(trait, options, o.getValueNum(), o.getValueText(), o.getValueDate());
        return o.getObservedOn() + " " + trait.getName() + " " + value;
    }

    private void audit(Long id, String action, String before, String after, long userId, LocalDateTime at) {
        AuditLog log = new AuditLog();
        log.setEntity(ENTITY);
        log.setEntityId(id);
        log.setAction(action);
        log.setOldValue(truncate(before));
        log.setNewValue(truncate(after));
        log.setUserId(userId);
        log.setCreatedAt(at);
        auditMapper.insert(log);
    }

    Map<Long, String> userNames() {
        return userMapper.selectList(null).stream().collect(Collectors.toMap(User::getId,
                u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName()));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 600 ? s : s.substring(0, 600);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
