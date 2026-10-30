package cn.tudijiance.importing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import cn.tudijiance.common.ApiException;

/**
 * 读电子表格：.xlsx、.xls（老版 Excel）和 .csv。每个单元格读成 String、BigDecimal 或 LocalDate，空格子是 null。
 * CSV 先按 UTF-8 解码，不是合法 UTF-8 时按 GB18030（中文 Excel 另存的 CSV 常是这种编码）。
 */
public final class SheetReader {

    static final int MAX_ROWS = 5000;
    static final int MAX_COLS = 200;

    private SheetReader() {
    }

    public record SheetData(String name, List<List<Object>> rows) {
    }

    public static List<SheetData> read(byte[] bytes, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return List.of(new SheetData("CSV", parseCsv(decode(bytes))));
        }
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            List<SheetData> sheets = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sheets.add(new SheetData(sheet.getSheetName(), readSheet(sheet, evaluator)));
            }
            return sheets;
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("读不了这个文件，请确认是 Excel（.xlsx、.xls）或 CSV 文件");
        }
    }

    private static List<List<Object>> readSheet(Sheet sheet, FormulaEvaluator evaluator) {
        List<List<Object>> rows = new ArrayList<>();
        int last = Math.min(sheet.getLastRowNum(), MAX_ROWS - 1);
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            List<Object> values = new ArrayList<>();
            if (row != null) {
                int lastCol = Math.min(row.getLastCellNum(), MAX_COLS);
                for (int c = 0; c < lastCol; c++) {
                    values.add(cellValue(row.getCell(c), evaluator));
                }
            }
            trimTrailingNulls(values);
            rows.add(values);
        }
        return rows;
    }

    private static Object cellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime t = cell.getLocalDateTimeCellValue();
                    yield t == null ? null : t.toLocalDate();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
            }
            case STRING -> {
                String s = cell.getStringCellValue().strip();
                yield s.isEmpty() ? null : s;
            }
            case BOOLEAN -> cell.getBooleanCellValue() ? "是" : "否";
            default -> null;
        };
    }

    private static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buf).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    /** CSV：逗号或制表符分隔，支持双引号括起来的内容（里面可以有逗号、换行，"" 表示一个引号） */
    static List<List<Object>> parseCsv(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        char sep = firstLine.chars().filter(ch -> ch == '\t').count() > firstLine.chars().filter(ch -> ch == ',').count()
                ? '\t' : ',';
        List<List<Object>> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                row.add(csvValue(cell));
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(csvValue(cell));
                cell.setLength(0);
                trimTrailingNulls(row);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() >= MAX_ROWS) {
                    return rows;
                }
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(csvValue(cell));
            trimTrailingNulls(row);
            rows.add(row);
        }
        return rows;
    }

    /** CSV 里纯数字的格子当数字，其余当文字（日期由导入时按项目类型再解析） */
    private static Object csvValue(StringBuilder cell) {
        String s = cell.toString().strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(s).stripTrailingZeros();
        }
        return s;
    }

    private static void trimTrailingNulls(List<Object> values) {
        while (!values.isEmpty() && values.getLast() == null) {
            values.removeLast();
        }
        if (values.size() > MAX_COLS) {
            values.subList(MAX_COLS, values.size()).clear();
        }
    }

    /** 单元格显示成文字（预览用） */
    public static String display(Object v) {
        return switch (v) {
            case null -> "";
            case BigDecimal d -> d.toPlainString();
            case LocalDate d -> d.toString();
            default -> v.toString();
        };
    }
}
