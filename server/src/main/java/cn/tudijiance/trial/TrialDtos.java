package cn.tudijiance.trial;

import java.util.List;

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
}
