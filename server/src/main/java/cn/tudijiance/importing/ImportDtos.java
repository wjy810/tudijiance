package cn.tudijiance.importing;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ImportDtos {

    private ImportDtos() {
    }

    public record PreviewSheet(String name, int rowCount, int colCount, List<List<String>> rows) {
    }

    public record Preview(List<PreviewSheet> sheets) {
    }

    /**
     * 列的对应关系。列号从 0 开始，表头行从 1 开始（和 Excel 左边的行号一样）。
     *
     * @param plotCodeCol    小区编号所在列；不填时按"品种 + 重复"在这一年的布局里找小区
     * @param observedOnCol  调查日期所在列（可不填）；日期类项目用格子里的日期
     * @param observedOn     没有调查日期列时，非日期项目都记成这一天
     * @param assignLayout   有小区编号时，按表格里的品种、重复设置这一年的布局
     * @param createVarieties 品种表里没有的品种自动新增
     * @param dryRun         只检查不导入
     */
    public record ImportMapping(
            @NotNull Long trialId,
            @Min(0) int sheet,
            @Min(1) int headerRow,
            Integer plotCodeCol,
            Integer varietyCol,
            Integer replicateCol,
            Integer observedOnCol,
            LocalDate observedOn,
            boolean assignLayout,
            boolean createVarieties,
            @NotNull @Size(max = 200) List<@Valid ColumnMapping> columns,
            boolean dryRun) {
    }

    /** 一列对应一个项目。项目是"折合亩产"时按小区面积换算成小区产量保存。 */
    public record ColumnMapping(@Min(0) int col, @NotNull Long traitId) {
    }

    /** row 是 Excel 里的行号（从 1 开始），col 是列字母 */
    public record CellError(int row, String col, String message) {
    }

    /**
     * @param created     导入了几个值（检查时是"将导入"）
     * @param duplicates  已经导入过、跳过的值
     */
    public record ImportResult(boolean dryRun, int rows, int created, int duplicates, int errorCount,
                               List<CellError> errors, List<String> newVarieties, int layoutChanges) {
    }
}
