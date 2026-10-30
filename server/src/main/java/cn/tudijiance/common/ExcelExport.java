package cn.tudijiance.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 生成一个工作表的 .xlsx：上面几行标题，接着表头和数据。
 * 数字写成数字单元格、日期写成日期单元格，在 Excel 里可以直接求和、排序。
 */
public final class ExcelExport {

    public static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private ExcelExport() {
    }

    public static byte[] write(String sheetName, List<String> titles, List<String> headers, List<List<Object>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Styles st = new Styles(wb);
            int r = 0;
            for (int i = 0; i < titles.size(); i++) {
                Row row = sheet.createRow(r);
                Cell cell = row.createCell(0);
                cell.setCellValue(titles.get(i));
                cell.setCellStyle(i == 0 ? st.title : st.subtitle);
                if (headers.size() > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(r, r, 0, headers.size() - 1));
                }
                r++;
            }
            int headerRow = r;
            Row head = sheet.createRow(r++);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(st.header);
            }
            int[] widths = new int[headers.size()];
            for (int c = 0; c < headers.size(); c++) {
                widths[c] = displayWidth(headers.get(c));
            }
            for (List<Object> values : rows) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size() && c < headers.size(); c++) {
                    Object v = values.get(c);
                    Cell cell = row.createCell(c);
                    switch (v) {
                        case null -> cell.setBlank();
                        case BigDecimal d -> {
                            cell.setCellValue(d.doubleValue());
                            cell.setCellStyle(st.number);
                        }
                        case Number n -> {
                            cell.setCellValue(n.doubleValue());
                            cell.setCellStyle(st.number);
                        }
                        case LocalDate d -> {
                            cell.setCellValue(d);
                            cell.setCellStyle(st.date);
                        }
                        case LocalDateTime t -> {
                            cell.setCellValue(t);
                            cell.setCellStyle(st.dateTime);
                        }
                        default -> {
                            cell.setCellValue(v.toString());
                            cell.setCellStyle(st.text);
                        }
                    }
                    widths[c] = Math.max(widths[c], v == null ? 0 : displayWidth(v instanceof LocalDateTime
                            ? "2026-01-01 00:00" : v instanceof BigDecimal d ? d.toPlainString() : v.toString()));
                }
            }
            for (int c = 0; c < widths.length; c++) {
                sheet.setColumnWidth(c, Math.min(widths[c] + 3, 60) * 256);
            }
            sheet.createFreezePane(0, headerRow + 1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 下载响应。文件名可以是中文。 */
    public static ResponseEntity<byte[]> response(String filename, byte[] body) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(body);
    }

    /** 中文按 2 个字符宽算 */
    private static int displayWidth(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) {
            w += ch > 0x2E80 ? 2 : 1;
        }
        return w;
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle text;
        final CellStyle number;
        final CellStyle date;
        final CellStyle dateTime;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);

            title = wb.createCellStyle();
            title.setFont(big);
            subtitle = wb.createCellStyle();

            header = bordered(wb);
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            text = bordered(wb);
            number = bordered(wb);
            number.setDataFormat(wb.createDataFormat().getFormat("General"));
            date = bordered(wb);
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            dateTime = bordered(wb);
            dateTime.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
        }

        private static CellStyle bordered(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }
}
