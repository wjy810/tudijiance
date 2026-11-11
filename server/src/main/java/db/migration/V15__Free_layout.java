package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 地块布局从"几行几列区块 + 合并"的表格（version 1）换成"随便摆的矩形"（version 2）：
 * <ul>
 *   <li>地块的大小 w、h 是原来各列宽、各行高加起来；</li>
 *   <li>每个区块按它原来占的行列算出位置和大小（特殊区块用它缩小后的位置和大小）；</li>
 *   <li>整行、整列都是道路的，变成一条通到头的道路；特殊区块四周空出来的地方原来画成道路，也补上道路；</li>
 *   <li>小区块网格（inner）原样不动，小区块、种植、数据都不受影响。</li>
 * </ul>
 */
public class V15__Free_layout extends BaseJavaMigration {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        Map<Long, String> layouts = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, layout FROM field WHERE layout IS NOT NULL")) {
            while (rs.next()) {
                layouts.put(rs.getLong(1), rs.getString(2));
            }
        }
        for (Map.Entry<Long, String> e : layouts.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            JsonNode old = JSON.readTree(e.getValue());
            if (!old.has("rows") || !old.has("cols")) {
                continue;
            }
            try (PreparedStatement st = conn.prepareStatement("UPDATE field SET layout = ? WHERE id = ?")) {
                st.setString(1, JSON.writeValueAsString(convert(old)));
                st.setLong(2, e.getKey());
                st.executeUpdate();
            }
        }
    }

    private record Box(double x, double y, double w, double h) {
    }

    /** 表格式的布局 → 矩形式的布局 */
    static ObjectNode convert(JsonNode old) {
        double[] rows = sizes(old.get("rows"));
        double[] cols = sizes(old.get("cols"));
        int nr = rows.length;
        int nc = cols.length;
        double[] ys = starts(rows);
        double[] xs = starts(cols);

        // 每个格子归哪个合并区域（左上角的行、列和跨度）
        int[][][] span = new int[nr][nc][];
        for (JsonNode m : old.path("merges")) {
            int[] sp = {m.path("r").asInt(), m.path("c").asInt(), m.path("rs").asInt(1), m.path("cs").asInt(1)};
            for (int r = sp[0]; r < Math.min(nr, sp[0] + sp[2]); r++) {
                for (int c = sp[1]; c < Math.min(nc, sp[1] + sp[3]); c++) {
                    span[r][c] = sp;
                }
            }
        }
        // 每个格子是什么类型（看盖住它的区块）
        String[][] kind = new String[nr][nc];
        List<JsonNode> blocks = new ArrayList<>();
        old.path("blocks").forEach(blocks::add);
        for (JsonNode b : blocks) {
            int r = b.path("r").asInt();
            int c = b.path("c").asInt();
            if (r < 0 || c < 0 || r >= nr || c >= nc) {
                continue;
            }
            int[] sp = span[r][c] != null && span[r][c][0] == r && span[r][c][1] == c ? span[r][c] : new int[] {r, c, 1, 1};
            for (int i = sp[0]; i < Math.min(nr, sp[0] + sp[2]); i++) {
                for (int j = sp[1]; j < Math.min(nc, sp[1] + sp[3]); j++) {
                    kind[i][j] = b.path("kind").asString("PLANT");
                }
            }
        }
        boolean[] roadRow = new boolean[nr];
        for (int r = 0; r < nr; r++) {
            roadRow[r] = true;
            for (int c = 0; c < nc; c++) {
                roadRow[r] &= "ROAD".equals(kind[r][c]);
            }
        }
        boolean[] roadCol = new boolean[nc];
        for (int c = 0; c < nc; c++) {
            roadCol[c] = true;
            for (int r = 0; r < nr; r++) {
                roadCol[c] &= "ROAD".equals(kind[r][c]);
            }
        }

        double w = round(xs[nc]);
        double h = round(ys[nr]);
        ArrayNode out = JSON.createArrayNode();
        Set<String> ids = new HashSet<>();
        blocks.forEach(b -> ids.add(b.path("id").asString("")));
        int[] next = {Math.max(1, old.path("nextBlockNo").asInt(1))};
        List<Box> extraRoads = new ArrayList<>();
        Set<Integer> doneRows = new HashSet<>();
        Set<Integer> doneCols = new HashSet<>();

        for (JsonNode b : blocks) {
            int r = b.path("r").asInt();
            int c = b.path("c").asInt();
            if (r < 0 || c < 0 || r >= nr || c >= nc) {
                continue;
            }
            int[] sp = span[r][c] != null && span[r][c][0] == r && span[r][c][1] == c ? span[r][c] : new int[] {r, c, 1, 1};
            int r2 = Math.min(nr, sp[0] + sp[2]);
            int c2 = Math.min(nc, sp[1] + sp[3]);
            Box slot = new Box(xs[c], ys[r], xs[c2] - xs[c], ys[r2] - ys[r]);
            String k = b.path("kind").asString("PLANT");
            if ("ROAD".equals(k)) {
                // 整行（列）道路上的一段：这一行（列）只出一条通到头的道路，用第一段的 id
                boolean inRow = sp[2] == 1 && roadRow[r];
                boolean inCol = sp[3] == 1 && roadCol[c];
                if (inRow) {
                    if (doneRows.add(r)) {
                        out.add(block(b, "ROAD", new Box(0, ys[r], w, rows[r]), null));
                    }
                    continue;
                }
                if (inCol) {
                    if (doneCols.add(c)) {
                        out.add(block(b, "ROAD", new Box(xs[c], 0, cols[c], h), null));
                    }
                    continue;
                }
            }
            Box rect = slot;
            JsonNode fit = b.get("fit");
            if (fit != null && fit.isObject()) {
                double fw = Math.min(slot.w(), fit.path("w").asDouble(slot.w()));
                double fh = Math.min(slot.h(), fit.path("h").asDouble(slot.h()));
                double fx = Math.max(0, Math.min(slot.w() - fw, fit.path("x").asDouble(0)));
                double fy = Math.max(0, Math.min(slot.h() - fh, fit.path("y").asDouble(0)));
                if (fw > 0 && fh > 0) {
                    rect = new Box(slot.x() + fx, slot.y() + fy, fw, fh);
                    // 原来的位置里空出来的地方画成道路
                    if (fy > 1e-6) {
                        extraRoads.add(new Box(slot.x(), slot.y(), slot.w(), fy));
                    }
                    if (fy + fh < slot.h() - 1e-6) {
                        extraRoads.add(new Box(slot.x(), rect.y() + fh, slot.w(), slot.h() - fy - fh));
                    }
                    if (fx > 1e-6) {
                        extraRoads.add(new Box(slot.x(), rect.y(), fx, fh));
                    }
                    if (fx + fw < slot.w() - 1e-6) {
                        extraRoads.add(new Box(rect.x() + fw, rect.y(), slot.w() - fx - fw, fh));
                    }
                }
            }
            out.add(block(b, k, rect, "PLANT".equals(k) ? b.get("inner") : null));
        }
        for (Box r : extraRoads) {
            if (r.w() < 0.01 || r.h() < 0.01) {
                continue;
            }
            String id;
            do {
                id = "B" + next[0]++;
            } while (!ids.add(id));
            ObjectNode road = JSON.createObjectNode();
            road.put("id", id);
            road.put("name", "道路");
            out.add(place(road, "ROAD", r));
        }

        ObjectNode layout = JSON.createObjectNode();
        layout.put("version", 2);
        layout.set("unit", old.get("unit"));
        layout.put("w", w);
        layout.put("h", h);
        layout.set("blocks", out);
        layout.put("nextBlockNo", next[0]);
        if (old.hasNonNull("defaultInner")) {
            layout.set("defaultInner", old.get("defaultInner"));
        }
        layout.put("northDeg", old.path("northDeg").asDouble(0));
        return layout;
    }

    private static ObjectNode block(JsonNode b, String kind, Box rect, JsonNode inner) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", b.path("id").asString(""));
        n.put("name", b.path("name").asString(""));
        place(n, kind, rect);
        if (inner != null && inner.isObject()) {
            n.set("inner", inner);
        }
        return n;
    }

    private static ObjectNode place(ObjectNode n, String kind, Box rect) {
        n.put("kind", kind);
        n.put("x", round(rect.x()));
        n.put("y", round(rect.y()));
        n.put("w", round(rect.w()));
        n.put("h", round(rect.h()));
        return n;
    }

    private static double[] sizes(JsonNode arr) {
        double[] out = new double[arr == null ? 0 : arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).asDouble();
        }
        return out;
    }

    private static double[] starts(double[] sizes) {
        double[] out = new double[sizes.length + 1];
        for (int i = 0; i < sizes.length; i++) {
            out[i + 1] = out[i] + sizes[i];
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
