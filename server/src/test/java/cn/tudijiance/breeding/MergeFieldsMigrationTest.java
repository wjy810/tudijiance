package cn.tudijiance.breeding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V13：原来的实验田每年的试验变成那一季的一块地块，品种变成原始材料。
 * 先迁到 V12，按原来的样子放好数据，再迁到最新，看数据搬得对不对。
 */
class MergeFieldsMigrationTest {

    private static final String URL =
            "jdbc:h2:mem:merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").target(target).load();
    }

    @Test
    void movesEachYearOfAnOldFieldIntoItsSeason() throws Exception {
        flyway("12").migrate();
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO app_user (id, username, password_hash, role, created_at) VALUES (900, 'u', 'x', 'TEACHER', NOW())");
            st.executeUpdate("INSERT INTO variety (id, name, created_at) VALUES (901, '周麦 36', NOW()), (902, '百农 207', NOW())");
            // 一块实验田：一个区块 1 × 2 个小区
            st.executeUpdate("INSERT INTO field (id, name, row_count, col_count, created_at, updated_at, layout) VALUES "
                    + "(910, '东区试验田', 1, 2, NOW(), NOW(), '{\"version\":1,\"unit\":{\"name\":\"米\",\"symbol\":\"m\",\"meters\":1},"
                    + "\"rows\":[10],\"cols\":[4],\"merges\":[],\"blocks\":[{\"id\":\"B1\",\"r\":0,\"c\":0,\"name\":\"区块1\",\"kind\":\"PLANT\","
                    + "\"inner\":{\"rows\":[1],\"cols\":[1,1],\"merges\":[],\"plots\":{\"0,0\":911,\"0,1\":912}}}],\"nextBlockNo\":2}')");
            st.executeUpdate("INSERT INTO plot (id, field_id, row_no, col_no, code, kind, length_m, width_m) VALUES "
                    + "(911, 910, 1, 1, '101', 'PLOT', 10, 2), (912, 910, 1, 2, '102', 'PLOT', 10, 2)");
            // 两年的试验
            st.executeUpdate("INSERT INTO trial (id, field_id, season_year, name, trial_type, created_at) VALUES "
                    + "(920, 910, 2024, '2024 区试', 'REGIONAL', NOW()), (921, 910, 2025, '2025 区试', 'REGIONAL', NOW())");
            st.executeUpdate("INSERT INTO planting (id, trial_id, plot_id, variety_id, replicate_no) VALUES "
                    + "(930, 920, 911, 901, 1), (931, 921, 911, 902, 1), (932, 921, 912, 901, 1)");
            st.executeUpdate("INSERT INTO observation (id, client_uuid, planting_id, trait_id, observed_on, value_num, source, "
                    + "recorded_by, recorded_at, version, deleted) VALUES (940, 'u1', 931, 1, '2025-05-01', 80, 'MANUAL', 900, NOW(), 1, FALSE)");
            st.executeUpdate("INSERT INTO control_link (id, trial_id, label, created_at) VALUES (950, 921, '对照', NOW())");
            st.executeUpdate("INSERT INTO control_link_plot (link_id, plot_id, is_control) VALUES (950, 911, FALSE), (950, 912, TRUE)");
            // 用户已经建过的季：同名的要接着用
            st.executeUpdate("INSERT INTO season (id, name, start_date, end_date, created_at) VALUES "
                    + "(960, '2024-2025 年度大田', '2024-10-10', '2025-06-06', NOW())");
        }

        flyway("latest").migrate();

        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            // 品种都是原始材料
            assertThat(list(c, "SELECT line_kind FROM variety WHERE id IN (901, 902)")).containsOnly("FOUNDER");
            assertThat(one(c, "SELECT line_code FROM variety WHERE id = 901")).isEqualTo("周麦 36");

            // 2024 年：原来的实验田进了"2023-2024 年度大田"
            Object s2024 = one(c, "SELECT season_id FROM field WHERE id = 910");
            assertThat(one(c, "SELECT name FROM season WHERE id = " + s2024)).isEqualTo("2023-2024 年度大田");
            assertThat(one(c, "SELECT field_id FROM trial WHERE id = 920").toString()).isEqualTo("910");

            // 2025 年：复制了一块地块，放进用户建过的同名季
            long copy = ((Number) one(c, "SELECT field_id FROM trial WHERE id = 921")).longValue();
            assertThat(copy).isNotEqualTo(910L);
            assertThat(one(c, "SELECT season_id FROM field WHERE id = " + copy).toString()).isEqualTo("960");
            assertThat(one(c, "SELECT name FROM field WHERE id = " + copy)).isEqualTo("东区试验田");
            List<Object> newPlots = list(c, "SELECT id FROM plot WHERE field_id = " + copy + " ORDER BY col_no");
            assertThat(newPlots).hasSize(2);
            assertThat(list(c, "SELECT code FROM plot WHERE field_id = " + copy + " ORDER BY col_no")).containsExactly("101", "102");
            // 种植记录、对照关系搬到新的小区块上，数据还挂在原来的种植记录上
            assertThat(one(c, "SELECT plot_id FROM planting WHERE id = 931").toString()).isEqualTo(newPlots.get(0).toString());
            assertThat(one(c, "SELECT plot_id FROM planting WHERE id = 932").toString()).isEqualTo(newPlots.get(1).toString());
            assertThat(one(c, "SELECT plot_id FROM planting WHERE id = 930").toString()).isEqualTo("911");
            assertThat(list(c, "SELECT plot_id FROM control_link_plot WHERE link_id = 950 ORDER BY plot_id").stream()
                    .map(Object::toString).toList()).containsExactlyInAnyOrder(newPlots.get(0).toString(), newPlots.get(1).toString());
            assertThat(one(c, "SELECT planting_id FROM observation WHERE id = 940").toString()).isEqualTo("931");
            // 新地块的布局指向新的小区块
            JsonNode layout = JsonMapper.builder().build().readTree(one(c, "SELECT layout FROM field WHERE id = " + copy).toString());
            assertThat(layout.get("blocks").get(0).get("inner").get("plots").get("0,0").asLong())
                    .isEqualTo(((Number) newPlots.get(0)).longValue());
            // 季按年份连起来
            assertThat(one(c, "SELECT prev_season_id FROM season_follow WHERE season_id = 960").toString())
                    .isEqualTo(s2024.toString());
        }
    }

    private static Object one(Connection c, String sql) throws Exception {
        List<Object> l = list(c, sql);
        assertThat(l).hasSize(1);
        return l.getFirst();
    }

    private static List<Object> list(Connection c, String sql) throws Exception {
        List<Object> out = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                Object v = rs.getObject(1);
                out.add(v instanceof java.sql.Clob ? rs.getString(1) : v);
            }
        }
        return out;
    }
}
