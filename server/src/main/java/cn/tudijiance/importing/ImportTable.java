package cn.tudijiance.importing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.importing.ImportDtos.CellEdit;
import cn.tudijiance.importing.ImportDtos.Orientation;
import cn.tudijiance.importing.ImportDtos.Range;

/**
 * 工作表里选中的一块区域，按"字段 × 记录"来读。
 * <ul>
 *   <li>按列（{@link Orientation#COLUMNS}）：区域的第一行是表头，每一列是一个字段，下面每一行是一条记录（一个小区）；</li>
 *   <li>按行（{@link Orientation#ROWS}）：区域的第一列是表头，每一行是一个字段，右边每一列是一条记录。</li>
 * </ul>
 * 读之前先套用页面上改过的格子。字段号、记录号都从 0 开始；出错时用 {@link #sheetRow}、{@link #sheetCol} 换回工作表里的位置。
 */
final class ImportTable {

    private final List<List<Object>> rows;
    private final Range range;
    private final Orientation orientation;
    private final Map<Long, Object> edits;

    private ImportTable(List<List<Object>> rows, Range range, Orientation orientation, Map<Long, Object> edits) {
        this.rows = rows;
        this.range = range;
        this.orientation = orientation;
        this.edits = edits;
    }

    /**
     * @param rows  整个工作表，每格是 String、BigDecimal、LocalDate 或 null
     * @param edits 改过的格子，内容按文字处理，空字符串表示清空
     */
    static ImportTable of(List<List<Object>> rows, Range range, Orientation orientation, List<CellEdit> edits) {
        Objects.requireNonNull(orientation, "orientation");
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        if (range.top() > range.bottom() || range.left() > range.right()) {
            throw ApiException.badRequest("选中的范围不正确");
        }
        if (range.bottom() >= rows.size() || range.right() >= Math.max(width, 1)) {
            throw ApiException.badRequest("选中的范围超出了表格");
        }
        int lines = orientation == Orientation.COLUMNS ? range.bottom() - range.top() : range.right() - range.left();
        if (lines < 1) {
            throw ApiException.badRequest(orientation == Orientation.COLUMNS
                    ? "选中的范围至少要有两行：第一行是表头，下面是数据"
                    : "选中的范围至少要有两列：第一列是表头，右边是数据");
        }
        Map<Long, Object> edited = new HashMap<>();
        if (edits != null) {
            for (CellEdit e : edits) {
                String v = e.value() == null ? "" : e.value().strip();
                edited.put(key(e.row(), e.col()), v.isEmpty() ? null : v);
            }
        }
        return new ImportTable(rows, range, orientation, edited);
    }

    /** 有几个字段（按列时是列数，按行时是行数） */
    int fieldCount() {
        return orientation == Orientation.COLUMNS ? range.right() - range.left() + 1 : range.bottom() - range.top() + 1;
    }

    /** 有几条记录（不含表头） */
    int recordCount() {
        return orientation == Orientation.COLUMNS ? range.bottom() - range.top() : range.right() - range.left();
    }

    /** 字段的表头文字 */
    String header(int field) {
        String text = CellValues.text(cellAt(sheetRow(-1, field), sheetCol(-1, field)));
        return text == null ? "" : text;
    }

    Object value(int record, int field) {
        return cellAt(sheetRow(record, field), sheetCol(record, field));
    }

    /** 这条记录在各字段上都是空的 */
    boolean isBlank(int record) {
        for (int f = 0; f < fieldCount(); f++) {
            if (value(record, f) != null) {
                return false;
            }
        }
        return true;
    }

    /** 记录在工作表里的行号（从 0 开始）；record 为 -1 表示表头 */
    int sheetRow(int record, int field) {
        return orientation == Orientation.COLUMNS ? range.top() + 1 + record : range.top() + field;
    }

    /** 记录在工作表里的列号（从 0 开始）；record 为 -1 表示表头 */
    int sheetCol(int record, int field) {
        return orientation == Orientation.COLUMNS ? range.left() + field : range.left() + 1 + record;
    }

    /** 所有字段的表头，按字段顺序 */
    List<String> headers() {
        List<String> list = new ArrayList<>();
        for (int f = 0; f < fieldCount(); f++) {
            list.add(header(f));
        }
        return list;
    }

    private Object cellAt(int r, int c) {
        long k = key(r, c);
        if (edits.containsKey(k)) {
            return edits.get(k);
        }
        if (r < 0 || r >= rows.size()) {
            return null;
        }
        List<Object> row = rows.get(r);
        return c < 0 || c >= row.size() ? null : row.get(c);
    }

    private static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }
}
