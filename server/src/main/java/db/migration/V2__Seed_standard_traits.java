package db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 导入 NY/T 1301—2025 附录 A 的预设性状和分级选项。
 * 数据在 resources/db/seed/traits-ny1301-2025.json，改数据请新增一个迁移，不要改这个文件。
 */
public class V2__Seed_standard_traits extends BaseJavaMigration {

    private static final String SEED = "/db/seed/traits-ny1301-2025.json";

    private static final String INSERT_TRAIT = """
            INSERT INTO trait_def (code, name, category, data_type, unit, precision_digits, agg_method,
                                   survey_time, std_clause, is_standard, is_computed, enabled, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
            """;

    private static final String INSERT_OPTION = """
            INSERT INTO trait_option (trait_id, value_code, label, description, sort_order)
            VALUES (?, ?, ?, ?, ?)
            """;

    @Override
    public void migrate(Context context) throws Exception {
        JsonNode traits = readSeed();
        Connection conn = context.getConnection();
        try (PreparedStatement traitStmt = conn.prepareStatement(INSERT_TRAIT, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement optionStmt = conn.prepareStatement(INSERT_OPTION)) {
            int order = 0;
            for (JsonNode t : traits) {
                order += 10;
                traitStmt.setString(1, t.get("code").asString());
                traitStmt.setString(2, t.get("name").asString());
                traitStmt.setString(3, t.get("category").asString());
                traitStmt.setString(4, t.get("dataType").asString());
                setNullableString(traitStmt, 5, t, "unit");
                if (t.has("precision")) {
                    traitStmt.setInt(6, t.get("precision").asInt());
                } else {
                    traitStmt.setNull(6, Types.INTEGER);
                }
                traitStmt.setString(7, t.get("agg").asString());
                setNullableString(traitStmt, 8, t, "survey");
                setNullableString(traitStmt, 9, t, "clause");
                traitStmt.setBoolean(10, !t.has("standard") || t.get("standard").asBoolean());
                traitStmt.setBoolean(11, t.has("computed") && t.get("computed").asBoolean());
                traitStmt.setInt(12, order);
                traitStmt.executeUpdate();

                long traitId;
                try (ResultSet keys = traitStmt.getGeneratedKeys()) {
                    keys.next();
                    traitId = keys.getLong(1);
                }
                JsonNode options = t.get("options");
                if (options == null) {
                    continue;
                }
                int optionOrder = 0;
                for (JsonNode o : options) {
                    optionStmt.setLong(1, traitId);
                    optionStmt.setString(2, o.get("v").asString());
                    optionStmt.setString(3, o.get("label").asString());
                    setNullableString(optionStmt, 4, o, "desc");
                    optionStmt.setInt(5, ++optionOrder);
                    optionStmt.executeUpdate();
                }
            }
        }
    }

    private static JsonNode readSeed() throws IOException {
        try (InputStream in = V2__Seed_standard_traits.class.getResourceAsStream(SEED)) {
            if (in == null) {
                throw new IllegalStateException("找不到种子数据 " + SEED);
            }
            return JsonMapper.builder().build().readTree(in);
        }
    }

    private static void setNullableString(PreparedStatement stmt, int index, JsonNode node, String key)
            throws java.sql.SQLException {
        if (node.has(key) && !node.get(key).isNull()) {
            stmt.setString(index, node.get(key).asString());
        } else {
            stmt.setNull(index, Types.VARCHAR);
        }
    }
}
