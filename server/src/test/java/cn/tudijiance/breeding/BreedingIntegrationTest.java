package cn.tudijiance.breeding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 育种的完整流程（需求一的验收场景）：季、地块、原始材料、整块种和按小区块种、选株、杂交、
 * 接着上一季的候选品系、世代、系谱式、淘汰整理、改名。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BreedingIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;

    private String teacher;
    private String student;
    private String sfx;

    @BeforeEach
    void login() throws Exception {
        String admin = loginAs("admin", "admin123");
        ensureUser(admin, "teacher1", "王老师", "TEACHER");
        ensureUser(admin, "student1", "张同学", "STUDENT");
        teacher = loginAs("teacher1", "secret123");
        student = loginAs("student1", "secret123");
        sfx = UUID.randomUUID().toString().substring(0, 4);
    }

    /** 一行两个区块（区块1、区块2），各 2 × 2 个小区块 */
    private static Map<String, Object> layout() {
        Map<String, Object> inner = Map.of("rows", List.of(1, 1), "cols", List.of(1, 1), "merges", List.of());
        return new HashMap<>(Map.of(
                "version", 1, "unit", Map.of("name", "米", "symbol", "m", "meters", 1.0),
                "rows", List.of(20), "cols", List.of(6, 6), "merges", List.of(),
                "blocks", List.of(
                        Map.of("id", "B1", "r", 0, "c", 0, "name", "区块1", "kind", "PLANT", "inner", inner),
                        Map.of("id", "B2", "r", 0, "c", 1, "name", "区块2", "kind", "PLANT", "inner", inner)),
                "nextBlockNo", 3, "defaultInner", Map.of("rows", 2, "cols", 2)));
    }

    @Test
    void breedingFlow() throws Exception {
        // 1. 第一季（全新开始），地块"东地"
        JsonNode s1 = call(teacher, post("/api/seasons"), Map.of("name", "2024-2025 年度大田 " + sfx,
                "startDate", "2024-10-01", "endDate", "2025-06-10"), status().isOk());
        long season1 = s1.get("season").get("id").asLong();
        assertThat(s1.get("followIds").size()).isZero();
        JsonNode east = call(teacher, post("/api/seasons/" + season1 + "/fields"),
                Map.of("name", "东地", "layout", layout()), status().isOk());
        long eastId = east.get("field").get("id").asLong();
        assertThat(east.get("field").get("seasonId").asLong()).isEqualTo(season1);
        assertThat(east.get("plots").size()).isEqualTo(8);
        List<Long> b1 = plotsOfBlock(east, "B1");
        List<Long> b2 = plotsOfBlock(east, "B2");
        long trial1 = landOf(season1, eastId).get("trialId").asLong();

        // 育种的地块不出现在原来品种比较的实验田列表里
        JsonNode comparison = call(teacher, get("/api/fields"), null, status().isOk());
        assertThat(ids(comparison)).doesNotContain(eastId);
        assertThat(ids(call(teacher, get("/api/fields?scope=breeding"), null, status().isOk()))).contains(eastId);
        call(teacher, delete("/api/fields/" + eastId), null, status().isBadRequest());

        // 2. 原始材料：一次建三个；名称里不能有"/"；学生不能建
        String zm = "周麦16" + sfx;
        String zh = "郑麦366" + sfx;
        String bn = "百农207" + sfx;
        JsonNode founders = call(teacher, post("/api/lines/founders"), Map.of("items", List.of(
                Map.of("name", zm, "ecotype", "SEMI_WINTER", "qualityType", "MEDIUM"),
                Map.of("name", zh, "ecotype", "SEMI_WINTER", "qualityType", "STRONG"),
                Map.of("name", bn))), status().isOk());
        long a = founders.get(0).get("id").asLong();
        long b = founders.get(1).get("id").asLong();
        long c = founders.get(2).get("id").asLong();
        assertThat(founders.get(0).get("code").asString()).isEqualTo(zm);
        assertThat(founders.get(0).get("kind").asString()).isEqualTo("FOUNDER");
        call(teacher, post("/api/lines/founders"), Map.of("items", List.of(Map.of("name", "a/b" + sfx))),
                status().isBadRequest());
        call(student, post("/api/lines/founders"), Map.of("items", List.of(Map.of("name", "x" + sfx))),
                status().isForbidden());

        // 3. 整块种：周麦16 种满区块1，再把其中 2 个小区块改种百农207；郑麦366 种在区块2 的两个小区块
        plant(eastId, b1, a, status().isOk());
        JsonNode layoutAfter = plant(eastId, b1.subList(2, 4), c, status().isOk());
        assertThat(plantingOf(layoutAfter, b1.get(0)).get("varietyId").asLong()).isEqualTo(a);
        assertThat(plantingOf(layoutAfter, b1.get(3)).get("varietyId").asLong()).isEqualTo(c);
        // 原始材料不标世代
        assertThat(plantingOf(layoutAfter, b1.get(0)).get("generation").isNull()).isTrue();
        plant(eastId, b2.subList(0, 2), b, status().isOk());
        // 育种的地块不能用原来"分配品种"的接口
        call(teacher, put("/api/trials/" + trial1 + "/plantings"),
                Map.of("plotIds", List.of(b2.get(2)), "attribute", "VARIETY", "value", a), status().isBadRequest());

        // 录了数据的小区块不能改种
        long heightTrait = traitId("plant_height");
        call(student, post("/api/observations"), Map.of("trialId", trial1, "traitId", heightTrait, "valueNum", 82,
                "items", List.of(Map.of("plotId", b1.get(0), "clientUuid", UUID.randomUUID().toString()))),
                status().isOk());
        plant(eastId, List.of(b1.get(0)), b, status().isConflict());

        // 4. 选株：从区块1 的周麦16 里一次选两株（挂牌 7 号、12 号）
        JsonNode sel = call(teacher, post("/api/lines/" + a + "/selections"), Map.of("count", 2,
                "tagNos", List.of("7", "12"), "fieldId", eastId, "blockId", "B1"), status().isOk());
        long a1 = sel.get(0).get("id").asLong();
        long a2 = sel.get(1).get("id").asLong();
        assertThat(sel.get(0).get("code").asString()).isEqualTo(zm + "-1");
        assertThat(sel.get(1).get("code").asString()).isEqualTo(zm + "-2");
        assertThat(sel.get(0).get("tagNo").asString()).isEqualTo("7");
        assertThat(sel.get(0).get("pedigree").asString()).isEqualTo(zm);
        assertThat(sel.get(0).get("ecotype").asString()).isEqualTo("SEMI_WINTER");
        // 区块2 没种周麦16，不能从那里选
        call(teacher, post("/api/lines/" + a + "/selections"), Map.of("count", 1, "fieldId", eastId, "blockId", "B2"),
                status().isBadRequest());
        // 选出时的单株数据
        JsonNode pv = call(teacher, put("/api/lines/" + a1 + "/plant-values"), Map.of("values", List.of(
                Map.of("traitId", heightTrait, "valueNum", 85))), status().isOk());
        assertThat(pv.get(0).get("display").asString()).isEqualTo("85 cm");

        // 5. 杂交：周麦16（母本）× 郑麦366（父本）
        JsonNode cross = call(teacher, post("/api/lines/crosses"), Map.of("motherId", a, "fatherId", b,
                "seasonId", season1), status().isOk());
        long ab = cross.get("line").get("id").asLong();
        String abCode = cross.get("line").get("code").asString();
        assertThat(abCode).matches("25-\\d{3,}");
        assertThat(cross.get("line").get("pedigree").asString()).isEqualTo(zm + "/" + zh);
        assertThat(cross.get("previous").size()).isZero();
        // 冬春性一样就继承，品质类型不一样就空着
        assertThat(cross.get("line").get("ecotype").asString()).isEqualTo("SEMI_WINTER");
        assertThat(cross.get("line").get("qualityType").isNull()).isTrue();
        call(teacher, post("/api/lines/crosses"), Map.of("motherId", a, "fatherId", a, "seasonId", season1),
                status().isBadRequest());

        // 6. 第二季"夏繁"接着第一季；地块"温室"照着东地的划分建
        JsonNode s2 = call(teacher, post("/api/seasons"), Map.of("name", "2025 夏繁 " + sfx,
                "startDate", "2025-06-20", "endDate", "2025-09-30", "followIds", List.of(season1)), status().isOk());
        long season2 = s2.get("season").get("id").asLong();
        JsonNode green = call(teacher, post("/api/seasons/" + season2 + "/fields"),
                Map.of("name", "温室", "copyFromFieldId", eastId), status().isOk());
        long greenId = green.get("field").get("id").asLong();
        assertThat(green.get("plots").size()).isEqualTo(8);
        assertThat(plotsOfBlock(green, "B1")).doesNotContainAnyElementsOf(b1);

        JsonNode cand = call(teacher, get("/api/breeding/fields/" + greenId + "/candidates"), null, status().isOk());
        assertThat(cand.get("fresh").asBoolean()).isFalse();
        assertThat(ids(group(cand, "prev").get("lines"))).contains(a, b, c);
        assertThat(ids(group(cand, "children").get("lines"))).contains(a1, a2, ab);

        List<Long> g1 = plotsOfBlock(green, "B1");
        JsonNode gl = plant(greenId, g1.subList(0, 2), ab, status().isOk());
        assertThat(plantingOf(gl, g1.get(0)).get("generation").asInt()).isEqualTo(1);
        plant(greenId, g1.subList(2, 4), a2, status().isOk());

        // 7. 第三季接着第一季和夏繁；新东地照着东地建
        JsonNode s3 = call(teacher, post("/api/seasons"), Map.of("name", "2025-2026 年度大田 " + sfx,
                "startDate", "2025-10-05", "endDate", "2026-06-05", "followIds", List.of(season1, season2)), status().isOk());
        long season3 = s3.get("season").get("id").asLong();
        assertThat(ids(s3.get("followIds"))).containsExactlyInAnyOrder(season1, season2);
        JsonNode newEast = call(teacher, post("/api/seasons/" + season3 + "/fields"),
                Map.of("name", "东地", "copyFromFieldId", eastId), status().isOk());
        long newEastId = newEast.get("field").get("id").asLong();
        List<Long> n1 = plotsOfBlock(newEast, "B1");
        List<Long> n2 = plotsOfBlock(newEast, "B2");
        // 同一季里地块不能重名
        call(teacher, post("/api/seasons/" + season3 + "/fields"), Map.of("name", "东地", "copyFromFieldId", eastId),
                status().isConflict());

        JsonNode nl = plant(newEastId, n1, ab, status().isOk());
        assertThat(plantingOf(nl, n1.get(0)).get("generation").asInt()).isEqualTo(2);
        plant(newEastId, n2.subList(0, 1), a1, status().isOk());
        plant(newEastId, n2.subList(1, 2), c, status().isOk());
        plant(newEastId, n2.subList(2, 3), a, status().isOk());
        plant(newEastId, n2.subList(3, 4), b, status().isOk());

        // 世代可以手动改
        JsonNode changed = call(teacher, put("/api/breeding/fields/" + newEastId + "/generation"),
                Map.of("plotIds", List.of(n1.get(0)), "generation", 3), status().isOk());
        assertThat(plantingOf(changed.get("layout"), n1.get(0)).get("generation").asInt()).isEqualTo(3);
        call(teacher, put("/api/breeding/fields/" + newEastId + "/generation"),
                Map.of("plotIds", List.of(n1.get(0)), "generation", 2), status().isOk());

        // 8. 从新东地的 25-xxx 里选 3 株；再做三交、重复杂交、回交
        JsonNode sel2 = call(teacher, post("/api/lines/" + ab + "/selections"), Map.of("count", 3,
                "plotId", n1.get(1)), status().isOk());
        assertThat(sel2.get(2).get("code").asString()).isEqualTo(abCode + "-3");
        long ab3 = sel2.get(2).get("id").asLong();
        JsonNode three = call(teacher, post("/api/lines/crosses"), Map.of("motherId", ab, "fatherId", c,
                "seasonId", season3), status().isOk());
        assertThat(three.get("line").get("code").asString()).matches("26-\\d{3,}");
        assertThat(three.get("line").get("pedigree").asString()).isEqualTo(zm + "/" + zh + "//" + bn);
        JsonNode again = call(teacher, post("/api/lines/crosses"), Map.of("motherId", a, "fatherId", b,
                "seasonId", season3), status().isOk());
        assertThat(again.get("previous").get(0).asString()).isEqualTo(abCode);
        JsonNode back = call(teacher, post("/api/lines/crosses"), Map.of("motherId", ab, "fatherId", a,
                "seasonId", season3), status().isOk());
        assertThat(back.get("line").get("pedigree").asString()).isEqualTo(zm + "*2/" + zh);

        // 选株后代第一次种：选出时 F2，下一季是 F3
        JsonNode s4 = call(teacher, post("/api/seasons"), Map.of("name", "2026 夏繁 " + sfx,
                "startDate", "2026-06-20", "followIds", List.of(season3)), status().isOk());
        long season4 = s4.get("season").get("id").asLong();
        JsonNode summer = call(teacher, post("/api/seasons/" + season4 + "/fields"),
                Map.of("name", "温室", "copyFromFieldId", greenId), status().isOk());
        long summerId = summer.get("field").get("id").asLong();
        JsonNode sl = plant(summerId, plotsOfBlock(summer, "B1").subList(0, 1), ab3, status().isOk());
        assertThat(plantingOf(sl, plotsOfBlock(summer, "B1").get(0)).get("generation").asInt()).isEqualTo(3);

        // 9. 淘汰整理：周麦16-2 夏繁种过，第三季没再种、也没有子代
        JsonNode cleanup = call(teacher, get("/api/seasons/" + season3 + "/cleanup"), null, status().isOk());
        assertThat(ids(cleanup)).contains(a2).doesNotContain(ab, a, b, c, a1);
        call(teacher, put("/api/lines/status"), Map.of("lineIds", List.of(a2), "status", "DROPPED"), status().isOk());
        JsonNode cand3 = call(teacher, get("/api/breeding/fields/" + newEastId + "/candidates"), null, status().isOk());
        List<Long> all3 = new ArrayList<>();
        cand3.get("groups").forEach(g -> all3.addAll(ids(g.get("lines"))));
        assertThat(all3).doesNotContain(a2);

        // 10. 系谱图：每个品系一行，放在杂交、选株的那一季
        JsonNode graph = call(student, get("/api/lines/graph"), null, status().isOk());
        JsonNode abRow = find(graph.get("lines"), r -> r.get("id").asLong() == ab);
        assertThat(abRow.get("homeSeasonId").asLong()).isEqualTo(season1);
        assertThat(ids(abRow.get("planted"), "seasonId")).containsExactly(season2, season3);
        JsonNode aRow = find(graph.get("lines"), r -> r.get("id").asLong() == a);
        assertThat(aRow.get("homeSeasonId").asLong()).isEqualTo(season1);
        assertThat(aRow.get("childCount").asInt()).isGreaterThanOrEqualTo(4);

        // 11. 品系详情：杂交组合种在哪、父母当季种在哪
        JsonNode detail = call(student, get("/api/lines/" + ab), null, status().isOk());
        assertThat(detail.get("originText").asString()).contains("杂交").contains(zm + "（母本）");
        assertThat(detail.get("plantings").size()).isEqualTo(6);
        JsonNode motherPlace = find(detail.get("parentPlaces"), p -> p.get("role").asString().equals("母本"));
        assertThat(motherPlace.get("fieldId").asLong()).isEqualTo(eastId);
        assertThat(ids(motherPlace.get("plotIds"))).containsExactlyInAnyOrderElementsOf(b1.subList(0, 2));
        JsonNode a1Detail = call(student, get("/api/lines/" + a1), null, status().isOk());
        assertThat(a1Detail.get("originText").asString()).contains("东地").contains("区块1").contains("挂牌 7 号");
        assertThat(a1Detail.get("plantValues").get(0).get("display").asString()).isEqualTo("85 cm");
        JsonNode aDetail = call(student, get("/api/lines/" + a), null, status().isOk());
        JsonNode firstSeason = aDetail.get("seasonValues").get(0);
        assertThat(firstSeason.get("seasonId").asLong()).isEqualTo(season1);
        JsonNode height = find(firstSeason.get("traits"), t -> t.get("traitId").asLong() == heightTrait);
        assertThat(height.get("display").asString()).isEqualTo("82 cm");

        // 12. 改名：编号不变，用旧名、编号都能找到
        call(teacher, put("/api/lines/" + a1), Map.of("name", "校麦1号" + sfx), status().isOk());
        JsonNode renamed = call(teacher, put("/api/lines/" + a1), Map.of("name", "校麦2号" + sfx), status().isOk());
        assertThat(renamed.get("code").asString()).isEqualTo(zm + "-1");
        assertThat(renamed.get("formerNames").asString()).isEqualTo("校麦1号" + sfx);
        assertThat(ids(call(teacher, get("/api/lines?keyword=校麦1号" + sfx), null, status().isOk()))).containsExactly(a1);
        assertThat(ids(call(teacher, get("/api/lines?keyword=" + zm + "-1"), null, status().isOk()))).contains(a1);
        JsonNode moreSel = call(teacher, post("/api/lines/" + a1 + "/selections"),
                Map.of("count", 1, "seasonId", season3), status().isOk());
        assertThat(moreSel.get(0).get("code").asString()).isEqualTo(zm + "-1-1");

        // 13. 有子代、种过的不能删；没用过的可以删
        call(teacher, delete("/api/lines/" + a), null, status().isConflict());
        JsonNode extra = call(teacher, post("/api/lines/founders"), Map.of("items", List.of(Map.of("name", "备用" + sfx))),
                status().isOk());
        call(teacher, delete("/api/lines/" + extra.get(0).get("id").asLong()), null, status().isOk());

        // 14. 季：有地块的不能删；季列表里有地块的统计
        call(teacher, delete("/api/seasons/" + season1), null, status().isConflict());
        JsonNode seasons = call(student, get("/api/seasons"), null, status().isOk());
        JsonNode s3view = find(seasons, s -> s.get("season").get("id").asLong() == season3);
        assertThat(s3view.get("fields").get(0).get("plantedCount").asInt()).isEqualTo(8);
        // 品种接口照旧返回全部（电脑端品种页只显示原来的品种和原始材料，名字给平面图、手机端用）
        JsonNode varieties = call(teacher, get("/api/varieties"), null, status().isOk());
        assertThat(ids(varieties)).contains(a).contains(ab);
    }

    private JsonNode plant(long fieldId, List<Long> plotIds, Long lineId, ResultMatcher expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("plotIds", plotIds);
        body.put("lineId", lineId);
        JsonNode r = call(teacher, put("/api/breeding/fields/" + fieldId + "/plantings"), body, expected);
        return r == null || !r.has("layout") ? r : r.get("layout");
    }

    private JsonNode landOf(long seasonId, long fieldId) throws Exception {
        JsonNode seasons = call(teacher, get("/api/seasons"), null, status().isOk());
        JsonNode s = find(seasons, x -> x.get("season").get("id").asLong() == seasonId);
        return find(s.get("fields"), f -> f.get("id").asLong() == fieldId);
    }

    private long traitId(String code) throws Exception {
        JsonNode traits = call(teacher, get("/api/traits"), null, status().isOk());
        return find(traits, t -> t.get("trait").get("code").asString().equals(code)).get("trait").get("id").asLong();
    }

    private static List<Long> plotsOfBlock(JsonNode detail, String blockId) {
        JsonNode block = find(detail.get("layout").get("blocks"), b -> b.get("id").asString().equals(blockId));
        List<Long> ids = new ArrayList<>();
        block.get("inner").get("plots").forEach(n -> ids.add(n.asLong()));
        ids.sort(Long::compare);
        return ids;
    }

    private static JsonNode plantingOf(JsonNode layout, long plotId) {
        return find(layout.get("plantings"), p -> p.get("plotId").asLong() == plotId);
    }

    private static JsonNode group(JsonNode candidates, String key) {
        return find(candidates.get("groups"), g -> g.get("key").asString().equals(key));
    }

    private static List<Long> ids(JsonNode array) {
        return ids(array, "id");
    }

    private static List<Long> ids(JsonNode array, String key) {
        List<Long> list = new ArrayList<>();
        array.forEach(n -> list.add(n.isNumber() ? n.asLong() : n.get(key).asLong()));
        return list;
    }

    private void ensureUser(String admin, String username, String displayName, String role) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "displayName", displayName,
                "role", role, "password", "secret123"));
        int code = mvc.perform(post("/api/users").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 409);
    }

    private String loginAs(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private JsonNode call(String token, MockHttpServletRequestBuilder request, Object body, ResultMatcher expected)
            throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        String response = mvc.perform(request).andExpect(expected).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? null : json.readTree(response);
    }

    private static JsonNode find(JsonNode array, Predicate<JsonNode> predicate) {
        for (JsonNode n : array) {
            if (predicate.test(n)) {
                return n;
            }
        }
        throw new AssertionError("没有找到符合条件的元素");
    }
}
