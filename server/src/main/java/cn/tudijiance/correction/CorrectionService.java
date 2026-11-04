package cn.tudijiance.correction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.observation.Observation;
import cn.tudijiance.observation.ObservationDtos.ObservationView;
import cn.tudijiance.observation.ObservationDtos.UpdateRequest;
import cn.tudijiance.observation.ObservationMapper;
import cn.tudijiance.observation.ObservationService;
import cn.tudijiance.observation.TrialContextLoader;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更正申请。学生不能直接改数据：提交"改成什么、为什么"，老师看过原值和新值后通过或驳回；
 * 通过时按申请改数据，修改记录里记下是按谁的申请改的。申请之后数据又被改过的，不能直接通过。
 */
@Service
public class CorrectionService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String KIND_UPDATE = "UPDATE";
    public static final String KIND_DELETE = "DELETE";

    public record CreateRequest(
            @NotNull Long observationId,
            @NotNull Integer version,
            @NotBlank String kind,
            LocalDate observedOn,
            BigDecimal valueNum,
            @Size(max = 500) String valueText,
            LocalDate valueDate,
            @Size(max = 500) String note,
            @NotBlank(message = "请写一下为什么要改") @Size(max = 500) String reason) {
    }

    public record ReviewRequest(@Size(max = 500) String note) {
    }

    /** 值都是显示用的文字（例如 "78 cm"），日期是调查日期 */
    public record CorrectionView(
            Long id, Long observationId, Long trialId, String trialName, Long fieldId, String fieldName,
            Long plotId, String plotCode, String varietyName, Long traitId, String traitName, String dataType, String kind,
            String oldValue, LocalDate oldObservedOn, String newValue, LocalDate newObservedOn,
            String currentValue, LocalDate currentObservedOn, boolean deleted, boolean stale,
            String reason, String status, Long requestedById, String requestedBy, LocalDateTime requestedAt,
            String reviewedBy, LocalDateTime reviewedAt, String reviewNote) {
    }

    /** 老师：待审核的个数；学生：自己待审核的个数，以及最近 7 天被处理了的个数 */
    public record Summary(long pending, long reviewedRecently) {
    }

    private final CorrectionRequestMapper mapper;
    private final ObservationService observations;
    private final ObservationMapper observationMapper;
    private final TrialContextLoader contextLoader;
    private final FieldService fieldService;
    private final Clock clock;

    public CorrectionService(CorrectionRequestMapper mapper, ObservationService observations,
                             ObservationMapper observationMapper, TrialContextLoader contextLoader,
                             FieldService fieldService, Clock clock) {
        this.mapper = mapper;
        this.observations = observations;
        this.observationMapper = observationMapper;
        this.contextLoader = contextLoader;
        this.fieldService = fieldService;
        this.clock = clock;
    }

    @Transactional
    public CorrectionView create(CreateRequest req, long userId) {
        Observation o = observations.require(req.observationId());
        if (!o.getVersion().equals(req.version())) {
            throw new ApiException(HttpStatus.CONFLICT, "这条数据刚被改过，请刷新后再申请");
        }
        if (mapper.selectCount(Wrappers.<CorrectionRequest>lambdaQuery()
                .eq(CorrectionRequest::getObservationId, o.getId()).eq(CorrectionRequest::getStatus, PENDING)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "这条数据已经有人申请更正了，等老师处理完再申请");
        }
        String reason = req.reason().trim();
        CorrectionRequest r = new CorrectionRequest();
        r.setObservationId(o.getId());
        r.setTrialId(observations.trialOf(o));
        r.setBaseVersion(o.getVersion());
        r.setOldDisplay(observations.displayValue(o));
        r.setOldObservedOn(o.getObservedOn());
        r.setReason(reason);
        r.setStatus(PENDING);
        r.setRequestedBy(userId);
        r.setRequestedAt(LocalDateTime.now(clock));
        switch (req.kind()) {
            case KIND_UPDATE -> {
                String note = req.note() == null ? o.getNote() : blankToNull(req.note());
                UpdateRequest change = new UpdateRequest(o.getVersion(), req.observedOn(), req.valueNum(),
                        req.valueText(), req.valueDate(), note);
                String after = observations.preview(o, change);
                boolean sameDate = req.observedOn() == null || req.observedOn().equals(o.getObservedOn());
                if (after.equals(r.getOldDisplay()) && sameDate && Objects.equals(note, o.getNote())) {
                    throw ApiException.badRequest("改的和原来一样");
                }
                r.setKind(KIND_UPDATE);
                r.setObservedOn(req.observedOn());
                r.setValueNum(req.valueNum());
                r.setValueText(req.valueText());
                r.setValueDate(req.valueDate());
                r.setNote(note);
                r.setNewDisplay(after);
            }
            case KIND_DELETE -> r.setKind(KIND_DELETE);
            default -> throw ApiException.badRequest("不认识的更正方式：" + req.kind());
        }
        mapper.insert(r);
        return views(List.of(r)).getFirst();
    }

    /** 老师看全部，学生只看自己的。待审核的排前面 */
    public List<CorrectionView> list(long userId, boolean teacher, String status, Long trialId) {
        List<CorrectionRequest> list = mapper.selectList(Wrappers.<CorrectionRequest>lambdaQuery()
                .eq(!teacher, CorrectionRequest::getRequestedBy, userId)
                .eq(status != null && !status.isBlank(), CorrectionRequest::getStatus, status)
                .eq(trialId != null, CorrectionRequest::getTrialId, trialId)
                .orderByDesc(CorrectionRequest::getId)
                .last("LIMIT 300"));
        List<CorrectionRequest> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing((CorrectionRequest r) -> !PENDING.equals(r.getStatus())));
        return views(sorted);
    }

    public Summary summary(long userId, boolean teacher) {
        long pending = mapper.selectCount(Wrappers.<CorrectionRequest>lambdaQuery()
                .eq(CorrectionRequest::getStatus, PENDING)
                .eq(!teacher, CorrectionRequest::getRequestedBy, userId));
        long recent = teacher ? 0 : mapper.selectCount(Wrappers.<CorrectionRequest>lambdaQuery()
                .eq(CorrectionRequest::getRequestedBy, userId)
                .in(CorrectionRequest::getStatus, APPROVED, REJECTED)
                .ge(CorrectionRequest::getReviewedAt, LocalDateTime.now(clock).minusDays(7)));
        return new Summary(pending, recent);
    }

    /** 通过：按申请改数据或删掉，修改记录里写上是按谁的申请 */
    @Transactional
    public CorrectionView approve(Long id, long teacherId, String note) {
        CorrectionRequest r = pending(id);
        Observation o = observationMapper.selectById(r.getObservationId());
        if (o == null || Boolean.TRUE.equals(o.getDeleted())) {
            throw new ApiException(HttpStatus.CONFLICT, "这条数据已经删了，这个申请只能驳回");
        }
        if (!o.getVersion().equals(r.getBaseVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "申请之后这条数据又被改过了，不能直接通过。可以驳回，让学生按现在的值重新申请");
        }
        String requester = observations.userNames().getOrDefault(r.getRequestedBy(), "?");
        String auditNote = "按" + requester + "的更正申请：" + r.getReason();
        if (KIND_DELETE.equals(r.getKind())) {
            observations.delete(o.getId(), teacherId, auditNote);
        } else {
            observations.update(o.getId(), new UpdateRequest(r.getBaseVersion(), r.getObservedOn(), r.getValueNum(),
                    r.getValueText(), r.getValueDate(), r.getNote()), teacherId, auditNote);
        }
        finish(r, APPROVED, teacherId, note);
        return views(List.of(mapper.selectById(id))).getFirst();
    }

    @Transactional
    public CorrectionView reject(Long id, long teacherId, String note) {
        CorrectionRequest r = pending(id);
        finish(r, REJECTED, teacherId, note);
        return views(List.of(mapper.selectById(id))).getFirst();
    }

    /** 申请人自己撤回还没处理的申请 */
    @Transactional
    public void withdraw(Long id, long userId) {
        CorrectionRequest r = pending(id);
        if (!r.getRequestedBy().equals(userId)) {
            throw ApiException.forbidden("只能撤回自己的申请");
        }
        finish(r, WITHDRAWN, null, null);
    }

    private CorrectionRequest pending(Long id) {
        CorrectionRequest r = mapper.selectById(id);
        if (r == null) {
            throw ApiException.notFound("更正申请");
        }
        if (!PENDING.equals(r.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "这个申请已经处理过了");
        }
        return r;
    }

    private void finish(CorrectionRequest r, String status, Long reviewer, String note) {
        int n = mapper.update(null, Wrappers.<CorrectionRequest>lambdaUpdate()
                .set(CorrectionRequest::getStatus, status)
                .set(CorrectionRequest::getReviewedBy, reviewer)
                .set(CorrectionRequest::getReviewedAt, LocalDateTime.now(clock))
                .set(CorrectionRequest::getReviewNote, blankToNull(note))
                .eq(CorrectionRequest::getId, r.getId())
                .eq(CorrectionRequest::getStatus, PENDING));
        if (n == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "这个申请已经处理过了");
        }
    }

    private List<CorrectionView> views(List<CorrectionRequest> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // 申请对应的数据（包括已经删了的），按试验一次取出小区、品种
        List<Long> obsIds = list.stream().map(CorrectionRequest::getObservationId).distinct().toList();
        Map<Long, Observation> obs = observationMapper.selectByIds(obsIds).stream()
                .collect(Collectors.toMap(Observation::getId, Function.identity()));
        Map<Long, List<Observation>> byTrial = new LinkedHashMap<>();
        list.forEach(r -> {
            Observation o = obs.get(r.getObservationId());
            if (o != null) {
                byTrial.computeIfAbsent(r.getTrialId(), k -> new ArrayList<>()).add(o);
            }
        });
        Map<Long, ObservationView> current = new HashMap<>();
        Map<Long, TrialInfo> trialInfo = new HashMap<>();
        Map<Long, Field> fields = new HashMap<>();
        byTrial.forEach((trialId, os) -> {
            var ctx = contextLoader.load(trialId);
            Field field = fields.computeIfAbsent(ctx.trial().getFieldId(), fieldService::get);
            trialInfo.put(trialId, new TrialInfo(ctx.trial().getName(), field.getId(), field.getName()));
            observations.views(os.stream().distinct().toList(), ctx).forEach(v -> current.put(v.id(), v));
        });
        Map<Long, String> users = observations.userNames();
        return list.stream().map(r -> {
            Observation o = obs.get(r.getObservationId());
            ObservationView v = current.get(r.getObservationId());
            TrialInfo info = trialInfo.getOrDefault(r.getTrialId(), new TrialInfo(null, null, null));
            boolean deleted = o == null || Boolean.TRUE.equals(o.getDeleted());
            boolean stale = PENDING.equals(r.getStatus()) && (deleted || !o.getVersion().equals(r.getBaseVersion()));
            return new CorrectionView(r.getId(), r.getObservationId(), r.getTrialId(), info.trialName(),
                    info.fieldId(), info.fieldName(),
                    v == null ? null : v.plotId(), v == null ? null : v.plotCode(), v == null ? null : v.varietyName(),
                    v == null ? null : v.traitId(), v == null ? null : v.traitName(), v == null ? null : v.dataType(),
                    r.getKind(),
                    r.getOldDisplay(), r.getOldObservedOn(), r.getNewDisplay(),
                    KIND_DELETE.equals(r.getKind()) ? null : r.getObservedOn() != null ? r.getObservedOn() : r.getOldObservedOn(),
                    deleted || v == null ? null : v.displayValue(), deleted || v == null ? null : v.observedOn(),
                    deleted, stale, r.getReason(), r.getStatus(), r.getRequestedBy(),
                    users.getOrDefault(r.getRequestedBy(), "?"), r.getRequestedAt(),
                    r.getReviewedBy() == null ? null : users.getOrDefault(r.getReviewedBy(), "?"), r.getReviewedAt(),
                    r.getReviewNote());
        }).toList();
    }

    private record TrialInfo(String trialName, Long fieldId, String fieldName) {
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
