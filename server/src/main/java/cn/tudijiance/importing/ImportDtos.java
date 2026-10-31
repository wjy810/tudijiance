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

    /**
     * 一个工作表的全部内容（最多 {@link SheetReader#MAX_ROWS} 行），每个格子都转成文字，页面上用来选范围、改格子。
     */
    public record PreviewSheet(String name, int rowCount, int colCount, List<List<String>> rows) {
    }

    public record Preview(List<PreviewSheet> sheets) {
    }

    /** 数据的方向 */
    public enum Orientation {
        /** 每列一个字段（第一行是表头），每行一条记录 */
        COLUMNS,
        /** 每行一个字段（第一列是表头），每列一条记录 */
        ROWS
    }

    /** 字段的用途 */
    public enum Role {
        /** 小区编号，用来找小区 */
        PLOT_CODE,
        /** 品种名称：有小区编号时可以按它设置布局；没有小区编号时和重复一起用来找小区 */
        VARIETY,
        /** 重复号 */
        REPLICATE,
        /** 调查日期：非日期类项目记在这一天 */
        OBSERVED_ON,
        /** 录入项目的值 */
        TRAIT
    }

    /** 选中的区域。行号、列号都从 0 开始，含首尾 */
    public record Range(@Min(0) int top, @Min(0) int left, @Min(0) int bottom, @Min(0) int right) {
    }

    /**
     * 区域里第 index 个字段（按列时是第几列，按行时是第几行，从 0 开始）的用途。没列出的字段不导入。
     *
     * @param traitId 用途是 TRAIT 时对应的录入项目
     */
    public record FieldMapping(@Min(0) int index, @NotNull Role role, Long traitId) {
    }

    /** 页面上改过的格子：工作表里的行号、列号（从 0 开始）和新内容，空字符串表示清空 */
    public record CellEdit(@Min(0) int row, @Min(0) int col, @Size(max = 500) String value) {
    }

    /**
     * 怎么导入。
     *
     * @param observedOn      没有调查日期字段时，非日期类项目都记成这一天
     * @param assignLayout    有小区编号时，按表格里的品种、重复设置这一年的布局
     * @param createVarieties 品种表里没有的品种自动新增
     * @param rememberHeaders 导入后把"表头 → 录入项目"记成录入项目的别名，下次自动认出
     * @param dryRun          只检查不导入
     */
    public record ImportMapping(
            @NotNull Long trialId,
            @Min(0) int sheet,
            @NotNull @Valid Range range,
            @NotNull Orientation orientation,
            @NotNull @Size(max = 200) List<@Valid FieldMapping> fields,
            LocalDate observedOn,
            boolean assignLayout,
            boolean createVarieties,
            @Size(max = 5000) List<@Valid CellEdit> edits,
            boolean rememberHeaders,
            boolean dryRun) {
    }

    /**
     * 出错的格子。
     *
     * @param row     Excel 里的行号（从 1 开始）
     * @param col     Excel 里的列字母
     * @param r       工作表里的行号（从 0 开始），页面上用来定位格子
     * @param c       工作表里的列号（从 0 开始）
     */
    public record CellError(int row, String col, int r, int c, String message) {
    }

    /**
     * @param records          有内容的记录数
     * @param created          导入了几个值（检查时是"将导入"）
     * @param duplicates       已经导入过、跳过的值
     * @param aliasesRemembered 新记下的表头别名个数
     */
    public record ImportResult(boolean dryRun, int records, int created, int duplicates, int errorCount,
                               List<CellError> errors, List<String> newVarieties, int layoutChanges,
                               int aliasesRemembered) {
    }
}
