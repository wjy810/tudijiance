package db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import cn.tudijiance.trait.AliasText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 合并《河南省小麦品种试验记载本》的字段：
 * <ul>
 *   <li>补上记载本有、NY/T 1301—2025 没有的录入项目（穗发芽分级、田间评价）；</li>
 *   <li>记载本里的表头（"亩成穗""条锈病反""第1次倒伏日期"等）记成对应录入项目的表头别名，导入时自动认出；</li>
 *   <li>记载本和标准写法不同的地方写进项目说明；用 1、3、5 记的选项补上级别，导入时写数字也能认出。</li>
 * </ul>
 * 数据在 resources/db/seed/record-book-henan-2024.json。已经有的别名、说明不覆盖（老师可能改过）。
 */
public class V6__Seed_record_book_fields extends BaseJavaMigration {

    private static final String SEED = "/db/seed/record-book-henan-2024.json";

    @Override
    public void migrate(Context context) throws Exception {
        JsonNode seed = readSeed();
        Connection conn = context.getConnection();
        insertTraits(conn, seed.get("traits"));
        for (Map.Entry<String, JsonNode> e : seed.get("optionDescriptions").properties()) {
            Long traitId = traitId(conn, e.getKey());
            if (traitId == null) {
                continue;
            }
            for (Map.Entry<String, JsonNode> o : e.getValue().properties()) {
                try (PreparedStatement st = conn.prepareStatement("""
                        UPDATE trait_option SET description = ?
                        WHERE trait_id = ? AND value_code = ? AND (description IS NULL OR description = '')
                        """)) {
                    st.setString(1, o.getValue().asString());
                    st.setLong(2, traitId);
                    st.setString(3, o.getKey());
                    st.executeUpdate();
                }
            }
        }
        for (Map.Entry<String, JsonNode> e : seed.get("descriptions").properties()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "UPDATE trait_def SET description = ? WHERE code = ? AND description IS NULL")) {
                st.setString(1, e.getValue().asString());
                st.setString(2, e.getKey());
                st.executeUpdate();
            }
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Map.Entry<String, JsonNode> e : seed.get("aliases").properties()) {
            Long traitId = traitId(conn, e.getKey());
            if (traitId == null) {
                continue;
            }
            for (JsonNode a : e.getValue()) {
                insertAlias(conn, traitId, AliasText.normalize(a.asString()), now);
            }
        }
    }

    private void insertTraits(Connection conn, JsonNode traits) throws SQLException {
        for (JsonNode t : traits) {
            String code = t.get("code").asString();
            if (traitId(conn, code) != null) {
                continue;
            }
            int order = sortOrderOf(conn, t.get("after").asString()) + 5;
            long traitId;
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO trait_def (code, name, category, data_type, unit, precision_digits, agg_method,
                                           survey_time, std_clause, is_standard, is_computed, enabled, sort_order)
                    VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, FALSE, FALSE, TRUE, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                st.setString(1, code);
                st.setString(2, t.get("name").asString());
                st.setString(3, t.get("category").asString());
                st.setString(4, t.get("dataType").asString());
                if (t.has("unit")) {
                    st.setString(5, t.get("unit").asString());
                } else {
                    st.setNull(5, Types.VARCHAR);
                }
                st.setString(6, t.get("agg").asString());
                st.setString(7, t.get("survey").asString());
                st.setString(8, t.get("clause").asString());
                st.setInt(9, order);
                st.executeUpdate();
                try (ResultSet keys = st.getGeneratedKeys()) {
                    keys.next();
                    traitId = keys.getLong(1);
                }
            }
            JsonNode options = t.get("options");
            if (options == null) {
                continue;
            }
            int optionOrder = 0;
            for (JsonNode o : options) {
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO trait_option (trait_id, value_code, label, sort_order) VALUES (?, ?, ?, ?)")) {
                    st.setLong(1, traitId);
                    st.setString(2, o.get("v").asString());
                    st.setString(3, o.get("label").asString());
                    st.setInt(4, ++optionOrder);
                    st.executeUpdate();
                }
            }
        }
    }

    /** 别名全局唯一：已经被别的项目占用、或者和某个项目的名称一样（按名称就能认出）时跳过 */
    private void insertAlias(Connection conn, long traitId, String alias, Timestamp now) throws SQLException {
        if (alias.isEmpty() || alias.length() > AliasText.MAX_LENGTH) {
            return;
        }
        try (PreparedStatement st = conn.prepareStatement("SELECT COUNT(*) FROM trait_alias WHERE alias = ?")) {
            st.setString(1, alias);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement st = conn.prepareStatement("SELECT name FROM trait_def WHERE id = ?")) {
            st.setLong(1, traitId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && AliasText.normalize(rs.getString(1)).equals(alias)) {
                    return;
                }
            }
        }
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO trait_alias (trait_id, alias, created_at) VALUES (?, ?, ?)")) {
            st.setLong(1, traitId);
            st.setString(2, alias);
            st.setTimestamp(3, now);
            st.executeUpdate();
        }
    }

    private static Long traitId(Connection conn, String code) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT id FROM trait_def WHERE code = ?")) {
            st.setString(1, code);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static int sortOrderOf(Connection conn, String code) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT sort_order FROM trait_def WHERE code = ?")) {
            st.setString(1, code);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1000;
            }
        }
    }

    private static JsonNode readSeed() throws IOException {
        try (InputStream in = V6__Seed_record_book_fields.class.getResourceAsStream(SEED)) {
            if (in == null) {
                throw new IllegalStateException("找不到种子数据 " + SEED);
            }
            return JsonMapper.builder().build().readTree(in);
        }
    }
}
