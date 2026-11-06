package cn.tudijiance.breeding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import cn.tudijiance.field.FieldLayout;
import cn.tudijiance.trial.TrialDtos.Layout;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class BreedingDtos {

    private BreedingDtos() {
    }

    // ---------- 季和地块 ----------

    /** followIds：接着哪一季（或几季）；为空表示"全新开始" */
    public record SeasonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 500) String remark,
            List<Long> followIds) {
    }

    public record LandSummary(Long id, String name, Long trialId, int plotCount, int plantedCount, int lineCount) {
    }

    public record SeasonView(Season season, List<Long> followIds, List<LandSummary> fields) {
    }

    /**
     * 新建地块：copyFromFieldId 照着以前某块地块的区块划分建（不带种植和数据）；否则用 layout（从空白开始画好的）
     */
    public record LandRequest(
            @NotBlank @Size(max = 100) String name,
            Long copyFromFieldId,
            FieldLayout layout,
            @Size(max = 200) String location,
            @Size(max = 500) String remark) {
    }

    // ---------- 品系 ----------

    public record FounderItem(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 64) String code,
            @Size(max = 200) String breeder,
            String ecotype,
            String qualityType,
            @Size(max = 500) String remark) {
    }

    public record FoundersRequest(@NotEmpty @Size(max = 500) List<@Valid FounderItem> items) {
    }

    /** 把原来品种比较里的品种拿来当原始材料 */
    public record AdoptRequest(@NotEmpty List<Long> varietyIds) {
    }

    /** 杂交：crossYear 不填时按这一季算；name 不填时名称就是编号 */
    public record CrossRequest(
            @NotNull Long motherId,
            @NotNull Long fatherId,
            @NotNull Long seasonId,
            @Min(2000) @Max(2100) Integer crossYear,
            @Size(max = 100) String name,
            @Size(max = 500) String remark) {
    }

    /**
     * 选株：一次选 count 株，tagNos 是各株的田间挂牌号（可以不填）。
     * 在平面图上选时给 plotId（小区块）或 fieldId + blockId（整个区块）；在系谱图里选时给 seasonId。
     */
    public record SelectionRequest(
            @NotNull @Min(1) @Max(200) Integer count,
            List<@Size(max = 32) String> tagNos,
            Long seasonId,
            Long fieldId,
            @Size(max = 32) String blockId,
            Long plotId) {
    }

    public record LineUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String breeder,
            String ecotype,
            String qualityType,
            @Size(max = 500) String remark) {
    }

    public record StatusRequest(@NotEmpty List<Long> lineIds, @NotBlank String status) {
    }

    public record PlantValueItem(@NotNull Long traitId, BigDecimal valueNum, String valueText, LocalDate valueDate) {
    }

    public record PlantValuesRequest(@NotNull List<@Valid PlantValueItem> values) {
    }

    /** 种植：给选中的小区块种上 lineId，lineId 为空表示清掉 */
    public record PlantRequest(@NotEmpty List<Long> plotIds, Long lineId) {
    }

    public record GenerationRequest(@NotEmpty List<Long> plotIds, @Min(1) @Max(40) Integer generation) {
    }

    /** 品系种过的季和那一季的世代 */
    public record PlantedIn(Long seasonId, Integer generation) {
    }

    /**
     * 品系的一行。pedigree：系谱式；parentText：父母的简短说明；homeSeasonId：系谱图里放在哪一季
     * （杂交、选株的季；原始材料是第一次种的季；还没种过的为空）
     */
    public record LineRow(
            Long id, String name, String code, String kind, String status, String pedigree,
            Long motherId, Long fatherId, Long parentId, String parentText,
            String formerNames, Long originSeasonId, Long homeSeasonId, List<PlantedIn> planted, int childCount,
            String ecotype, String qualityType, String breeder, String remark, String tagNo, Integer seqNo) {
    }

    public record GraphView(List<Season> seasons, List<LineRow> lines) {
    }

    public record CandidateGroup(String key, String title, List<LineRow> lines) {
    }

    public record Candidates(Long seasonId, boolean fresh, List<CandidateGroup> groups) {
    }

    /** previous：同样的父母以前做过的组合编号 */
    public record CrossResult(LineRow line, List<String> previous) {
    }

    public record PlantingRow(Long seasonId, String seasonName, Long fieldId, String fieldName, Long trialId,
                              Long plotId, String plotCode, String blockId, String blockName, String place,
                              Integer generation) {
    }

    /** 父母在某一季种在哪：打开那块地块并高亮这些小区块 */
    public record ParentPlace(Long lineId, String lineName, String role, Long seasonId, String seasonName,
                              Long fieldId, String fieldName, List<Long> plotIds, String place) {
    }

    public record PlantValueView(Long traitId, String traitName, BigDecimal valueNum, String valueText,
                                 LocalDate valueDate, String display) {
    }

    public record PlotValueRow(Long plotId, String place, String display) {
    }

    public record TraitValueRow(Long traitId, String traitName, String category, int sortOrder, boolean important,
                                BigDecimal average, String display, int count, List<PlotValueRow> plots) {
    }

    public record SeasonValues(Long seasonId, String seasonName, Integer generation, List<TraitValueRow> traits) {
    }

    public record LineDetail(LineRow line, String originText, List<LineRow> parents, List<LineRow> children,
                             List<ParentPlace> parentPlaces, List<PlantingRow> plantings,
                             List<PlantValueView> plantValues, List<SeasonValues> seasonValues) {
    }

    public record PlantResult(Layout layout, List<String> notes) {
    }
}
