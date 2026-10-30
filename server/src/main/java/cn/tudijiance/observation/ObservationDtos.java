package cn.tudijiance.observation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ObservationDtos {

    private ObservationDtos() {
    }

    /** 录入到一个小区。clientUuid 由客户端生成，同一条数据重复提交只保存一次。 */
    public record EntryItem(@NotNull Long plotId, @NotBlank @Size(max = 36) String clientUuid) {
    }

    /** 一次给多个小区录入同一个项目、同一个值（例如同一天给整块田施肥）。 */
    public record EntryRequest(
            @NotNull Long trialId,
            @NotNull Long traitId,
            @NotEmpty @Size(max = 500) List<@Valid EntryItem> items,
            LocalDate observedOn,
            BigDecimal valueNum,
            @Size(max = 500) String valueText,
            LocalDate valueDate,
            @Size(max = 500) String note) {
    }

    /** 老师修改一条数据。version 用来发现"别人刚改过"的情况。 */
    public record UpdateRequest(
            @NotNull Integer version,
            LocalDate observedOn,
            BigDecimal valueNum,
            @Size(max = 500) String valueText,
            LocalDate valueDate,
            @Size(max = 500) String note) {
    }

    public record ObservationView(
            Long id, Long trialId, Long plotId, String plotCode, Long varietyId, String varietyName,
            Integer replicateNo, Long traitId, String traitName, String traitCategory, String dataType,
            String unit, boolean standardTrait, LocalDate observedOn, BigDecimal valueNum, String valueText,
            LocalDate valueDate, String displayValue, String note, String source,
            String recordedBy, LocalDateTime recordedAt, String updatedBy, LocalDateTime updatedAt, Integer version) {
    }

    public record PageResult<T>(long total, List<T> items) {
    }

    public record HistoryView(String action, String oldValue, String newValue, String user, LocalDateTime at) {
    }

    /** 平面图右键"标记异常"或取消异常。 */
    public record AbnormalRequest(@NotEmpty List<Long> plotIds, @NotNull Boolean abnormal, @Size(max = 200) String note) {
    }
}
