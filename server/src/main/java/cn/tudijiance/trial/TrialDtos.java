package cn.tudijiance.trial;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TrialDtos {

    private TrialDtos() {
    }

    public record TrialRequest(
            @NotNull Long fieldId,
            @NotNull @Min(2000) @Max(2100) Integer seasonYear,
            @NotBlank @Size(max = 100) String name,
            @NotBlank String trialType,
            String wheatZone,
            @Min(1) @Max(20) Integer replicateCount,
            @Size(max = 500) String remark) {
    }

    /** 批量设置小区的品种或重复号。value 为 null 表示清除。 */
    public record AssignRequest(
            @NotEmpty List<Long> plotIds,
            @NotNull Attribute attribute,
            Long value) {
    }

    public enum Attribute { VARIETY, REPLICATE }

    /** 新建对照关系。controlPlotIds 为空时，自动把种了对照品种的小区当作对照。 */
    public record LinkRequest(
            @NotEmpty @Size(min = 2, max = 50) List<Long> plotIds,
            List<Long> controlPlotIds,
            @Size(max = 50) String label) {
    }

    public record LinkView(Long id, String label, List<LinkPlotView> plots) {
    }

    public record LinkPlotView(Long plotId, boolean control) {
    }

    public record PlantingView(Long plotId, Long varietyId, Integer replicateNo) {
    }

    public record Layout(Trial trial, List<PlantingView> plantings, List<LinkView> links) {
    }

    /**
     * 试验记载本。info：封面、试验设计、栽培管理、产量计算参数等（字段 → 值）；
     * weather：气象资料（"行|月|cur 或 norm" → 值）；evaluations：品种评价（品种 id → 评价）。
     */
    public record TrialBookRequest(Map<String, String> info, Map<String, String> weather,
                                   Map<String, String> evaluations) {
    }

    /** harvestAreaM2：按产量计算参数算出的收获面积，折合亩产用它；为空时按小区长 × 宽 */
    public record TrialBookView(Long trialId, Map<String, String> info, Map<String, String> weather,
                                Map<String, String> evaluations, BigDecimal harvestAreaM2,
                                LocalDateTime updatedAt, String updatedBy) {
    }
}
