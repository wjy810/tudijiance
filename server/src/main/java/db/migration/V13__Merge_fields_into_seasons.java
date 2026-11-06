package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把原来"品种比较"的实验田、试验、品种并进育种的季、地块、品系，两套合成一套：
 * <ul>
 *   <li>品种都变成原始材料（编号用名称，名称被别的编号占了时加上 id）；</li>
 *   <li>原来的实验田每一年的试验变成那一季（"2024-2025 年度大田"）的一块地块：
 *       第一年用原来的实验田，以后各年复制一块（区块划分和小区块一样），种植记录、对照关系跟着搬过去，
 *       录入的数据、记载本、更正申请都还挂在原来的试验上；</li>
 *   <li>没有试验的实验田按建的时间放进一季，补一个试验；</li>
 *   <li>按年份连起来的季：后一季接着前一季。</li>
 * </ul>
 */
public class V13__Merge_fields_into_seasons extends BaseJavaMigration {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<Integer, Long> seasonsByYear = new TreeMap<>();

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        varietiesToFounders(conn);
        fieldsToLands(conn);
        followByYear(conn);
    }

    // ---------- 品种 → 原始材料 ----------

    private void varietiesToFounders(Connection conn) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM variety WHERE line_kind IS NULL ORDER BY id")) {
            while (rs.next()) {
                rows.add(new Object[] {rs.getLong(1), rs.getString(2)});
            }
        }
        for (Object[] r : rows) {
            long id = (Long) r[0];
            String name = (String) r[1];
            String code = name.length() > 60 ? name.substring(0, 60) : name;
            if (exists(conn, "SELECT COUNT(*) FROM variety WHERE line_code = ?", code)) {
                code = code + "-" + id;
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "UPDATE variety SET line_kind = 'FOUNDER', line_code = ?, line_status = 'ACTIVE' WHERE id = ?")) {
                st.setString(1, code);
                st.setLong(2, id);
                st.executeUpdate();
            }
        }
    }

    // ---------- 实验田 → 季里的地块 ----------

    private void fieldsToLands(Connection conn) throws SQLException {
        List<long[]> fields = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        Map<Long, Timestamp> createdAt = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, created_at FROM field WHERE season_id IS NULL ORDER BY id")) {
            while (rs.next()) {
                fields.add(new long[] {rs.getLong(1)});
                names.put(rs.getLong(1), rs.getString(2));
                createdAt.put(rs.getLong(1), rs.getTimestamp(3));
            }
        }
        for (long[] f : fields) {
            long fieldId = f[0];
            List<long[]> trials = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT id, season_year FROM trial WHERE field_id = ? ORDER BY season_year, id")) {
                st.setLong(1, fieldId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        trials.add(new long[] {rs.getLong(1), rs.getInt(2)});
                    }
                }
            }
            if (trials.isEmpty()) {
                LocalDateTime at = createdAt.get(fieldId) == null ? LocalDateTime.now() : createdAt.get(fieldId).toLocalDateTime();
                int year = at.getMonthValue() >= 8 ? at.getYear() + 1 : at.getYear();
                long seasonId = season(conn, year);
                setSeason(conn, fieldId, seasonId, landName(conn, seasonId, names.get(fieldId), fieldId));
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO trial (field_id, season_year, name, trial_type, created_at) VALUES (?, ?, ?, 'BREEDING', ?)
                        """)) {
                    st.setLong(1, fieldId);
                    st.setInt(2, year);
                    st.setString(3, cut(seasonName(year) + " · " + names.get(fieldId)));
                    st.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
                    st.executeUpdate();
                }
                continue;
            }
            // 第一年：原来的实验田
            long firstSeason = season(conn, (int) trials.getFirst()[1]);
            setSeason(conn, fieldId, firstSeason, landName(conn, firstSeason, names.get(fieldId), fieldId));
            // 以后各年：复制一块地块，把那一年的试验搬过去
            for (long[] t : trials.subList(1, trials.size())) {
                long seasonId = season(conn, (int) t[1]);
                Map<Long, Long> plotMap = new HashMap<>();
                long copy = copyField(conn, fieldId, seasonId, landName(conn, seasonId, names.get(fieldId), null), plotMap);
                movePlantings(conn, t[0], plotMap);
                moveLinks(conn, t[0], plotMap);
                try (PreparedStatement st = conn.prepareStatement("UPDATE trial SET field_id = ? WHERE id = ?")) {
                    st.setLong(1, copy);
                    st.setLong(2, t[0]);
                    st.executeUpdate();
                }
            }
        }
    }

    private void setSeason(Connection conn, long fieldId, long seasonId, String name) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("UPDATE field SET season_id = ?, name = ? WHERE id = ?")) {
            st.setLong(1, seasonId);
            st.setString(2, name);
            st.setLong(3, fieldId);
            st.executeUpdate();
        }
    }

    /** 同一季里地块不重名：重名时加上"（原实验田）"、再重名加序号 */
    private String landName(Connection conn, long seasonId, String name, Long self) throws SQLException {
        String candidate = name;
        int n = 1;
        while (true) {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM field WHERE season_id = ? AND name = ?" + (self == null ? "" : " AND id <> ?"))) {
                st.setLong(1, seasonId);
                st.setString(2, candidate);
                if (self != null) {
                    st.setLong(3, self);
                }
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) == 0) {
                        return cut(candidate);
                    }
                }
            }
            candidate = name + (n == 1 ? "（原实验田）" : "（原实验田 " + n + "）");
            n++;
        }
    }

    /** 复制一块地：实验田那一行、全部小区块，布局里的小区块 id 换成新的 */
    private long copyField(Connection conn, long fieldId, long seasonId, String name, Map<Long, Long> plotMap)
            throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        try (PreparedStatement st = conn.prepareStatement("SELECT * FROM field WHERE id = ?")) {
            st.setLong(1, fieldId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String col = md.getColumnLabel(i).toLowerCase();
                    if (!col.equals("id")) {
                        row.put(col, value(rs, i));
                    }
                }
            }
        }
        row.put("season_id", seasonId);
        row.put("name", name);
        long copy = insert(conn, "field", row);

        List<Map<String, Object>> plots = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement("SELECT * FROM plot WHERE field_id = ? ORDER BY id")) {
            st.setLong(1, fieldId);
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        p.put(md.getColumnLabel(i).toLowerCase(), value(rs, i));
                    }
                    plots.add(p);
                }
            }
        }
        for (Map<String, Object> p : plots) {
            long oldId = ((Number) p.remove("id")).longValue();
            p.put("field_id", copy);
            plotMap.put(oldId, insert(conn, "plot", p));
        }

        Object layout = row.get("layout");
        if (layout != null) {
            JsonNode tree = JSON.readTree(layout.toString());
            for (JsonNode b : tree.path("blocks")) {
                JsonNode plotsNode = b.path("inner").path("plots");
                if (plotsNode instanceof ObjectNode obj) {
                    for (Map.Entry<String, JsonNode> e : new ArrayList<>(obj.properties())) {
                        Long mapped = plotMap.get(e.getValue().asLong());
                        if (mapped != null) {
                            obj.put(e.getKey(), mapped);
                        }
                    }
                }
            }
            try (PreparedStatement st = conn.prepareStatement("UPDATE field SET layout = ? WHERE id = ?")) {
                st.setString(1, JSON.writeValueAsString(tree));
                st.setLong(2, copy);
                st.executeUpdate();
            }
        }
        return copy;
    }

    private void movePlantings(Connection conn, long trialId, Map<Long, Long> plotMap) throws SQLException {
        for (Map.Entry<Long, Long> e : plotMap.entrySet()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "UPDATE planting SET plot_id = ? WHERE trial_id = ? AND plot_id = ?")) {
                st.setLong(1, e.getValue());
                st.setLong(2, trialId);
                st.setLong(3, e.getKey());
                st.executeUpdate();
            }
        }
    }

    private void moveLinks(Connection conn, long trialId, Map<Long, Long> plotMap) throws SQLException {
        List<Long> links = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement("SELECT id FROM control_link WHERE trial_id = ?")) {
            st.setLong(1, trialId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    links.add(rs.getLong(1));
                }
            }
        }
        for (Long link : links) {
            for (Map.Entry<Long, Long> e : plotMap.entrySet()) {
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE control_link_plot SET plot_id = ? WHERE link_id = ? AND plot_id = ?")) {
                    st.setLong(1, e.getValue());
                    st.setLong(2, link);
                    st.setLong(3, e.getKey());
                    st.executeUpdate();
                }
            }
        }
    }

    // ---------- 季 ----------

    static String seasonName(int year) {
        return (year - 1) + "-" + year + " 年度大田";
    }

    /** 这一年度的季：有同名的就用它，没有就建一个（10 月 1 日到第二年 6 月 10 日） */
    private long season(Connection conn, int year) throws SQLException {
        Long known = seasonsByYear.get(year);
        if (known != null) {
            return known;
        }
        String name = seasonName(year);
        try (PreparedStatement st = conn.prepareStatement("SELECT id FROM season WHERE name = ?")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    seasonsByYear.put(year, rs.getLong(1));
                    return rs.getLong(1);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("start_date", Date.valueOf(LocalDate.of(year - 1, 10, 1)));
        row.put("end_date", Date.valueOf(LocalDate.of(year, 6, 10)));
        row.put("remark", "由原来的实验田试验转来");
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now()));
        long id = insert(conn, "season", row);
        seasonsByYear.put(year, id);
        return id;
    }

    /** 转来的季按年份连起来：后一季接着前一季（已经设过"接着"的不动） */
    private void followByYear(Connection conn) throws SQLException {
        Integer prev = null;
        for (Map.Entry<Integer, Long> e : seasonsByYear.entrySet()) {
            if (prev != null && prev == e.getKey() - 1) {
                long id = e.getValue();
                if (!exists(conn, "SELECT COUNT(*) FROM season_follow WHERE season_id = ?", id)) {
                    try (PreparedStatement st = conn.prepareStatement(
                            "INSERT INTO season_follow (season_id, prev_season_id) VALUES (?, ?)")) {
                        st.setLong(1, id);
                        st.setLong(2, seasonsByYear.get(prev));
                        st.executeUpdate();
                    }
                }
            }
            prev = e.getKey();
        }
    }

    // ---------- 工具 ----------

    /** 长文本（H2 里是 CLOB）按字符串取，才能原样写回去 */
    private static Object value(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        return v instanceof java.sql.Clob ? rs.getString(i) : v;
    }

    private static long insert(Connection conn, String table, Map<String, Object> row) throws SQLException {
        String cols = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map(k -> "?").toList());
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            for (Object v : row.values()) {
                st.setObject(i++, v);
            }
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static boolean exists(Connection conn, String sql, Object arg) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, arg);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static String cut(String s) {
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
