package cn.tudijiance.field;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class FieldDtos {

    private FieldDtos() {
    }

    /**
     * 新建或修改实验田。修改行列数时，网格会扩大或缩小。
     * plotLengthM、plotWidthM 是小区的默认长和宽，用于新生成的小区；单个小区的尺寸可以再单独改。
     */
    public record FieldRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String location,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal altitudeM,
            BigDecimal areaMu,
            @NotNull @Min(1) @Max(100) Integer rowCount,
            @NotNull @Min(1) @Max(100) Integer colCount,
            @Size(max = 50) String soilType,
            Boolean irrigated,
            @Size(max = 50) String previousCrop,
            @Size(max = 500) String remark,
            @DecimalMin("0.1") @DecimalMax("200") BigDecimal plotLengthM,
            @DecimalMin("0.1") @DecimalMax("200") BigDecimal plotWidthM,
            FieldLayout layout) {
    }

    /**
     * layout：区块布局；还没保存过布局的老实验田给的是按行列算出来的（整块地一个区块），layoutSaved 为 false。
     */
    public record FieldDetail(Field field, List<Plot> plots, FieldLayout layout, boolean layoutSaved) {
    }

    /** 批量改小区编号：key 是小区 id，value 是新编号（空字符串表示清除编号）。 */
    public record PlotCodesRequest(@NotEmpty Map<Long, String> codes) {
    }

    /** 批量改小区属性：只修改不为 null 的项。 */
    public record PlotPatchRequest(
            @NotEmpty List<Long> plotIds,
            String kind,
            BigDecimal lengthM,
            BigDecimal widthM,
            Integer plantingRows,
            BigDecimal rowSpacingCm) {
    }
}
