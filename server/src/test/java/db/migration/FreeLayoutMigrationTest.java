package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import cn.tudijiance.field.FieldLayout;
import cn.tudijiance.field.LayoutGeometry;

/**
 * V15：表格式的布局（几行几列区块 + 合并）换成随便摆的矩形。位置和大小要和原来画出来的一样，小区块都留着。
 */
class FreeLayoutMigrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNIT = "\"unit\":{\"name\":\"米\",\"symbol\":\"m\",\"meters\":1}";
    private static final String INNER = "\"inner\":{\"rows\":[1,1],\"cols\":[1,1,1],\"merges\":[],\"plots\":{\"0,0\":%d}}";

    private static String plant(String id, int r, int c, int plot) {
        return "{\"id\":\"" + id + "\",\"r\":" + r + ",\"c\":" + c + ",\"name\":\"" + id + "\",\"kind\":\"PLANT\",\"fit\":null,"
                + INNER.formatted(plot) + "}";
    }

    private static String road(String id, int r, int c) {
        return "{\"id\":\"" + id + "\",\"r\":" + r + ",\"c\":" + c + ",\"name\":\"道路\",\"kind\":\"ROAD\",\"fit\":null,\"inner\":null}";
    }

    private static JsonNode block(JsonNode layout, String id) {
        for (JsonNode b : layout.get("blocks")) {
            if (b.get("id").asString().equals(id)) {
                return b;
            }
        }
        return null;
    }

    private static List<Double> rect(JsonNode b) {
        return List.of(b.get("x").asDouble(), b.get("y").asDouble(), b.get("w").asDouble(), b.get("h").asDouble());
    }

    /** 2 行 2 列区块，中间横竖各一条道路：横向道路合并成一整行，纵向道路被它隔成两段 */
    private static final String GRID = "{\"version\":1," + UNIT + ",\"rows\":[13.3,1,13.3],\"cols\":[6,1.5,6],"
            + "\"merges\":[{\"r\":1,\"c\":0,\"rs\":1,\"cs\":3}],\"blocks\":["
            + plant("B1", 0, 0, 1) + "," + road("R2", 0, 1) + "," + plant("B2", 0, 2, 2) + ","
            + road("R1", 1, 0) + ","
            + plant("B3", 2, 0, 3) + "," + road("R3", 2, 1) + "," + plant("B4", 2, 2, 4)
            + "],\"nextBlockNo\":5,\"defaultInner\":{\"rows\":2,\"cols\":3},\"northDeg\":30}";

    @Test
    void gridBecomesFreeRectanglesWithWholeRoads() throws Exception {
        JsonNode l = V15__Free_layout.convert(JSON.readTree(GRID));
        assertThat(l.get("version").asInt()).isEqualTo(2);
        assertThat(l.get("w").asDouble()).isEqualTo(13.5);
        assertThat(l.get("h").asDouble()).isEqualTo(27.6);
        assertThat(l.get("northDeg").asDouble()).isEqualTo(30.0);
        assertThat(l.get("defaultInner").get("cols").asInt()).isEqualTo(3);
        assertThat(l.has("rows")).isFalse();
        assertThat(rect(block(l, "B1"))).containsExactly(0.0, 0.0, 6.0, 13.3);
        assertThat(rect(block(l, "B4"))).containsExactly(7.5, 14.3, 6.0, 13.3);
        assertThat(block(l, "B4").get("inner").get("plots").get("0,0").asInt()).isEqualTo(4);
        assertThat(block(l, "B1").has("r")).isFalse();
        // 横向道路一条通到头；纵向道路的两段并成一条，也通到头
        assertThat(rect(block(l, "R1"))).containsExactly(0.0, 13.3, 13.5, 1.0);
        assertThat(rect(block(l, "R2"))).containsExactly(6.0, 0.0, 1.5, 27.6);
        assertThat(block(l, "R3")).isNull();
        assertThat(l.get("blocks").size()).isEqualTo(6);
        // 转出来的布局能通过检查，小区块的行号列号和原来一样
        FieldLayout parsed = JSON.treeToValue(l, FieldLayout.class);
        LayoutGeometry.validate(parsed);
        LayoutGeometry.Flat flat = LayoutGeometry.flatten(parsed);
        assertThat(flat.rowCount()).isEqualTo(4);
        assertThat(flat.colCount()).isEqualTo(6);
        LayoutGeometry.Leaf b4 = flat.leaves().stream().filter(x -> x.block().id().equals("B4")).findFirst().orElseThrow();
        assertThat(b4.rowNo()).isEqualTo(3);
        assertThat(b4.colNo()).isEqualTo(4);
    }

    @Test
    void mergedAndShrunkBlocksKeepTheirPlace() throws Exception {
        // 第一行两个区块合并成一个；第二行左边的区块缩小了（特殊区块，四周原来画成道路），右边是单独一块道路
        String json = "{\"version\":1," + UNIT + ",\"rows\":[10,10],\"cols\":[6,6],"
                + "\"merges\":[{\"r\":0,\"c\":0,\"rs\":1,\"cs\":2}],\"blocks\":["
                + plant("B1", 0, 0, 1) + ","
                + "{\"id\":\"B2\",\"r\":1,\"c\":0,\"name\":\"B2\",\"kind\":\"PLANT\",\"fit\":{\"x\":1,\"y\":2,\"w\":4,\"h\":8},"
                + INNER.formatted(2) + "},"
                + road("B3", 1, 1) + "],\"nextBlockNo\":4}";
        JsonNode l = V15__Free_layout.convert(JSON.readTree(json));
        assertThat(rect(block(l, "B1"))).containsExactly(0.0, 0.0, 12.0, 10.0);
        assertThat(rect(block(l, "B2"))).containsExactly(1.0, 12.0, 4.0, 8.0);
        // 不是整行整列的道路：还是原来那一块
        assertThat(rect(block(l, "B3"))).containsExactly(6.0, 10.0, 6.0, 10.0);
        // 缩小后空出来的三条边补上道路
        List<List<Double>> extra = new ArrayList<>();
        for (JsonNode b : l.get("blocks")) {
            if (b.get("id").asString().matches("B[4-9]")) {
                assertThat(b.get("kind").asString()).isEqualTo("ROAD");
                extra.add(rect(b));
            }
        }
        assertThat(extra).containsExactlyInAnyOrder(
                List.of(0.0, 10.0, 6.0, 2.0), List.of(0.0, 12.0, 1.0, 8.0), List.of(5.0, 12.0, 1.0, 8.0));
        assertThat(l.get("nextBlockNo").asInt()).isEqualTo(7);
        LayoutGeometry.validate(JSON.treeToValue(l, FieldLayout.class));
    }

    @Test
    void convertsStoredLayoutsAndLeavesTheRestAlone() throws Exception {
        String url = "jdbc:h2:mem:freelayout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("14").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO field (id, name, row_count, col_count, created_at, updated_at, layout) VALUES "
                    + "(910, '有布局的', 4, 6, NOW(), NOW(), '" + GRID + "'), (911, '老网格', 2, 3, NOW(), NOW(), NULL)");
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT layout FROM field WHERE id = 910")) {
                assertThat(rs.next()).isTrue();
                JsonNode l = JSON.readTree(rs.getString(1));
                assertThat(l.get("version").asInt()).isEqualTo(2);
                assertThat(rect(block(l, "B2"))).containsExactly(7.5, 0.0, 6.0, 13.3);
            }
            try (ResultSet rs = st.executeQuery("SELECT layout FROM field WHERE id = 911")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }
        }
    }
}
