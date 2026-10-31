package cn.tudijiance.analysis;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    /** 按什么分组 */
    public enum Dimension {
        VARIETY, YEAR, REPLICATE, PLOT, FIELD,
        /** 按某个选项类、分级类项目的取值分组，例如按"粒质""条锈病反应型" */
        TRAIT
    }

    /** 一组里多个值怎么汇总 */
    public enum Agg {
        AVG, SUM, MAX, MIN, MEDIAN,
        /** 有值的小区个数 */
        COUNT,
        /** 变异系数：标准差 ÷ 平均值 × 100%，看稳定性 */
        CV
    }

    /** @param traitId 按项目取值分组（TRAIT）时用 */
    public record DimensionSpec(@NotNull Dimension type, Long traitId) {
    }

    /** @param traitId 统计哪个项目；汇总方式是 COUNT 且不填时数小区个数 */
    public record MeasureSpec(Long traitId, @NotNull Agg agg) {
    }

    /**
     * 自助分析的查询：范围（实验田、年份或试验、品种、重复、小区）+ 分组 + 统计的项目。
     * 不分组时返回每个小区（种植记录）一行，用来画散点图、看明细。
     */
    public record AnalysisQuery(
            Long fieldId,
            @Size(max = 50) List<Integer> years,
            @Size(max = 50) List<Long> trialIds,
            @Size(max = 500) List<Long> varietyIds,
            @Size(max = 20) List<Integer> replicates,
            @Size(max = 2000) List<Long> plotIds,
            @NotNull @Size(max = 2) List<@Valid DimensionSpec> dimensions,
            @NotEmpty @Size(max = 8) List<@Valid MeasureSpec> measures) {
    }

    /** @param kind DIMENSION 或 MEASURE */
    public record Column(String key, String label, String unit, String kind) {
    }

    /**
     * @param rows    每行先是各分组的名称，再是各统计值（没有数据时为 null）
     * @param records 参与统计的种植记录（小区 × 年份）个数
     */
    public record AnalysisResult(List<Column> columns, List<List<Object>> rows, int records) {
    }

    // ---------- 保存的图表 ----------

    /** @param config 图表设置（范围、字段、图表类型），由客户端定义 */
    public record ChartRequest(@NotBlank @Size(max = 100) String name, @NotNull JsonNode config) {
    }

    public record ChartView(Long id, String name, JsonNode config, Long createdBy, String createdByName,
                            LocalDateTime updatedAt) {
    }
}
