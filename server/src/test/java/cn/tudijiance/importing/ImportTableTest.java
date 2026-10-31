package cn.tudijiance.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.importing.ImportDtos.CellEdit;
import cn.tudijiance.importing.ImportDtos.Orientation;
import cn.tudijiance.importing.ImportDtos.Range;

class ImportTableTest {

    /** 标题行 + 表头 + 两条记录；第 0 列是空的 */
    private static final List<List<Object>> SHEET = List.of(
            row(null, "2025 年记载表"),
            row(null, "小区", "株高", "穗数"),
            row(null, "101", new BigDecimal("78"), new BigDecimal("41.5")),
            row(null, "102", new BigDecimal("80")));

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    @Test
    void readsColumnsAsFieldsInsideTheRange() {
        ImportTable t = ImportTable.of(SHEET, new Range(1, 1, 3, 3), Orientation.COLUMNS, List.of());
        assertThat(t.fieldCount()).isEqualTo(3);
        assertThat(t.recordCount()).isEqualTo(2);
        assertThat(t.headers()).containsExactly("小区", "株高", "穗数");
        assertThat(t.value(0, 2)).isEqualTo(new BigDecimal("41.5"));
        assertThat(t.value(1, 2)).isNull();
        // 记录 1 的第 2 个字段在工作表的第 3 行、第 3 列
        assertThat(t.sheetRow(1, 2)).isEqualTo(3);
        assertThat(t.sheetCol(1, 2)).isEqualTo(3);
    }

    @Test
    void readsRowsAsFieldsWhenTransposed() {
        List<List<Object>> sheet = List.of(
                row("品种", "济麦 22", "周麦 36"),
                row("株高", new BigDecimal("72"), new BigDecimal("78")));
        ImportTable t = ImportTable.of(sheet, new Range(0, 0, 1, 2), Orientation.ROWS, List.of());
        assertThat(t.fieldCount()).isEqualTo(2);
        assertThat(t.recordCount()).isEqualTo(2);
        assertThat(t.headers()).containsExactly("品种", "株高");
        assertThat(t.value(1, 0)).isEqualTo("周麦 36");
        assertThat(t.value(1, 1)).isEqualTo(new BigDecimal("78"));
        assertThat(t.sheetRow(1, 1)).isEqualTo(1);
        assertThat(t.sheetCol(1, 1)).isEqualTo(2);
    }

    @Test
    void appliesEditsBeforeReading() {
        ImportTable t = ImportTable.of(SHEET, new Range(1, 1, 3, 3), Orientation.COLUMNS,
                List.of(new CellEdit(3, 3, " 40 "), new CellEdit(2, 2, ""), new CellEdit(1, 3, "有效穗数")));
        assertThat(t.value(1, 2)).isEqualTo("40");
        assertThat(t.value(0, 1)).isNull();
        assertThat(t.header(2)).isEqualTo("有效穗数");
    }

    @Test
    void blankRecordsAreDetected() {
        List<List<Object>> sheet = List.of(row("小区", "株高"), row(), row("101", "70"));
        ImportTable t = ImportTable.of(sheet, new Range(0, 0, 2, 1), Orientation.COLUMNS, List.of());
        assertThat(t.isBlank(0)).isTrue();
        assertThat(t.isBlank(1)).isFalse();
    }

    @Test
    void rejectsRangesOutsideTheSheetOrWithoutData() {
        assertThatThrownBy(() -> ImportTable.of(SHEET, new Range(1, 1, 9, 3), Orientation.COLUMNS, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("超出");
        assertThatThrownBy(() -> ImportTable.of(SHEET, new Range(1, 1, 1, 3), Orientation.COLUMNS, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("至少要有两行");
        assertThatThrownBy(() -> ImportTable.of(SHEET, new Range(1, 1, 3, 1), Orientation.ROWS, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("至少要有两列");
        assertThatThrownBy(() -> ImportTable.of(SHEET, new Range(3, 1, 1, 3), Orientation.COLUMNS, List.of()))
                .isInstanceOf(ApiException.class);
    }
}
