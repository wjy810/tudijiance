package cn.tudijiance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.device.Device;
import cn.tudijiance.device.DeviceMapper;
import cn.tudijiance.ingest.DeviceStatusJob;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用 H2（MySQL 模式）跑完整的接口流程：登录、实验田、品种、试验布局、对照关系、设备和传感器上报。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private DeviceStatusJob deviceStatusJob;

    private String adminToken;

    @BeforeEach
    void login() throws Exception {
        adminToken = loginAs("admin", "admin123");
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        mvc.perform(get("/api/fields")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recorderCannotChangeConfiguration() throws Exception {
        call(post("/api/users"), Map.of("username", "student1", "displayName", "学生",
                "role", "RECORDER", "password", "secret123"), status().isOk());
        String recorder = loginAs("student1", "secret123");
        mvc.perform(post("/api/fields").header("Authorization", "Bearer " + recorder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"rowCount\":1,\"colCount\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/fields").header("Authorization", "Bearer " + recorder))
                .andExpect(status().isOk());
    }

    @Test
    void seedsStandardTraits() throws Exception {
        JsonNode traits = call(get("/api/traits"), null, status().isOk());
        assertThat(traits.size()).isGreaterThan(80);
        JsonNode lodging = find(traits, t -> t.get("trait").get("code").asString().equals("lodging_final_grade"));
        assertThat(lodging.get("trait").get("name").asString()).isEqualTo("最终倒伏程度");
        assertThat(lodging.get("trait").get("aggMethod").asString()).isEqualTo("MAX");
        assertThat(lodging.get("options").size()).isEqualTo(5);
        assertThat(lodging.get("options").get(2).get("description").asString()).contains("30°–45°");
    }

    @Test
    void fieldGridNumberingAndPlotKinds() throws Exception {
        JsonNode field = call(post("/api/fields"), Map.of("name", "东区试验田", "rowCount", 3, "colCount", 4),
                status().isOk());
        long fieldId = field.get("field").get("id").asLong();
        JsonNode plots = field.get("plots");
        assertThat(plots.size()).isEqualTo(12);
        assertThat(plots.get(0).get("code").asString()).isEqualTo("1");
        assertThat(plots.get(11).get("code").asString()).isEqualTo("12");

        long p1 = plots.get(0).get("id").asLong();
        long p2 = plots.get(1).get("id").asLong();
        // 两个小区互换编号
        JsonNode swapped = call(put("/api/fields/" + fieldId + "/plot-codes"),
                Map.of("codes", Map.of(String.valueOf(p1), "2", String.valueOf(p2), "1")), status().isOk());
        assertThat(swapped.get(0).get("code").asString()).isEqualTo("2");
        assertThat(swapped.get(1).get("code").asString()).isEqualTo("1");
        // 和别的小区重复
        call(put("/api/fields/" + fieldId + "/plot-codes"), Map.of("codes", Map.of(String.valueOf(p1), "5")),
                status().isBadRequest());

        JsonNode patched = call(patch("/api/fields/" + fieldId + "/plots"),
                Map.of("plotIds", List.of(p1), "kind", "GUARD", "lengthM", 6.65), status().isOk());
        assertThat(patched.get(0).get("kind").asString()).isEqualTo("GUARD");
        assertThat(patched.get(0).get("lengthM").decimalValue()).isEqualByComparingTo("6.65");

        // 扩大网格到 4 × 4，新增的一行小区没有编号
        JsonNode resized = call(put("/api/fields/" + fieldId),
                Map.of("name", "东区试验田", "rowCount", 4, "colCount", 4), status().isOk());
        assertThat(resized.get("plots").size()).isEqualTo(16);
    }

    @Test
    void trialLayoutWithControlLink() throws Exception {
        JsonNode field = call(post("/api/fields"), Map.of("name", "西区试验田", "rowCount", 2, "colCount", 3),
                status().isOk());
        long fieldId = field.get("field").get("id").asLong();
        List<Long> plotIds = new ArrayList<>();
        field.get("plots").forEach(p -> plotIds.add(p.get("id").asLong()));

        long ck = call(post("/api/varieties"), Map.of("name", "周麦18-测试", "controlType", "YIELD"),
                status().isOk()).get("id").asLong();
        long test = call(post("/api/varieties"), Map.of("name", "新品系A-测试", "ecotype", "SEMI_WINTER"),
                status().isOk()).get("id").asLong();

        long trialId = call(post("/api/trials"), Map.of("fieldId", fieldId, "seasonYear", 2026,
                "name", "2026 品比试验", "trialType", "COMPARISON", "wheatZone", "Q3"), status().isOk())
                .get("id").asLong();

        String trialUrl = "/api/trials/" + trialId;
        call(put(trialUrl + "/plantings"), Map.of("plotIds", List.of(plotIds.get(0)),
                "attribute", "VARIETY", "value", ck), status().isOk());
        call(put(trialUrl + "/plantings"), Map.of("plotIds", List.of(plotIds.get(1), plotIds.get(2)),
                "attribute", "VARIETY", "value", test), status().isOk());
        JsonNode layout = call(put(trialUrl + "/plantings"), Map.of("plotIds", plotIds.subList(0, 3),
                "attribute", "REPLICATE", "value", 1), status().isOk());
        assertThat(layout.get("plantings").size()).isEqualTo(3);

        // 没有指定对照小区时，自动把种了对照品种的小区当对照
        layout = call(post(trialUrl + "/control-links"), Map.of("plotIds", plotIds.subList(0, 2)), status().isOk());
        JsonNode link = layout.get("links").get(0);
        assertThat(link.get("label").asString()).isEqualTo("对照品种");
        JsonNode controlPlot = find(link.get("plots"), p -> p.get("control").asBoolean());
        assertThat(controlPlot.get("plotId").asLong()).isEqualTo(plotIds.get(0));

        // 两个都不是对照品种，又没指定对照
        call(post(trialUrl + "/control-links"), Map.of("plotIds", plotIds.subList(1, 3)), status().isBadRequest());

        // 保护区不能分配品种
        call(patch("/api/fields/" + fieldId + "/plots"), Map.of("plotIds", List.of(plotIds.get(5)), "kind", "GUARD"),
                status().isOk());
        call(put(trialUrl + "/plantings"), Map.of("plotIds", List.of(plotIds.get(5)),
                "attribute", "VARIETY", "value", test), status().isBadRequest());

        // 品种被使用时不能删除
        call(delete("/api/varieties/" + ck), null, status().isConflict());
        layout = call(delete(trialUrl + "/control-links/" + link.get("id").asLong()), null, status().isOk());
        assertThat(layout.get("links").size()).isZero();
    }

    @Test
    void deviceIngestOverHttp() throws Exception {
        JsonNode created = call(post("/api/devices"), Map.of("code", "SOIL-T01", "name", "测试土壤传感器",
                "deviceType", "SOIL_3IN1", "depthCm", 10), status().isOk());
        String token = created.get("token").asString();
        long deviceId = created.get("device").get("id").asLong();
        assertThat(created.get("device").get("mqttTopic").asString()).isEqualTo("tudi/devices/SOIL-T01/up");
        assertThat(created.get("device").get("status").asString()).isEqualTo("NEVER");

        String payload = """
                {"ts": "2026-10-30T08:00:00+08:00",
                 "values": {"soil_temp": 12.3, "soil_moisture": "23.5", "soil_ph": 7.12, "air_temp": 15}}
                """;
        JsonNode result = ingest("SOIL-T01", token, payload, status().isOk());
        assertThat(result.get("accepted").asInt()).isEqualTo(3);
        assertThat(result.get("rejected").get(0).asString()).contains("air_temp").contains("未启用");

        // DTU 断线后重发同一条数据，不会重复入库
        result = ingest("SOIL-T01", token, payload, status().isOk());
        assertThat(result.get("accepted").asInt()).isZero();
        assertThat(result.get("duplicates").asInt()).isEqualTo(3);

        // 批量补发，pH 超出合理范围的标记出来
        result = ingest("SOIL-T01", token, """
                {"records": [
                  {"ts": "2026-10-30T08:30:00", "values": {"soil_ph": 12.5}},
                  {"ts": "2026-10-30T09:00:00", "values": {"soil_ph": 7.10}}]}
                """, status().isOk());
        assertThat(result.get("accepted").asInt()).isEqualTo(2);

        ingest("SOIL-T01", "wrong-token", payload, status().isUnauthorized());
        ingest("SOIL-T01", token, "not json", status().isBadRequest());

        JsonNode devices = call(get("/api/devices"), null, status().isOk());
        JsonNode device = find(devices, d -> d.get("code").asString().equals("SOIL-T01"));
        assertThat(device.get("status").asString()).isEqualTo("ONLINE");
        assertThat(device.get("latest").get("soil_ph").get("value").decimalValue()).isEqualByComparingTo("7.10");
        assertThat(device.get("latest").get("soil_temp").get("value").decimalValue()).isEqualByComparingTo("12.3");

        JsonNode series = call(get("/api/devices/" + deviceId + "/readings?metric=soil_ph&bucket=HOUR"
                + "&from=2026-10-30T00:00:00&to=2026-10-30T23:59:59"), null, status().isOk());
        assertThat(series.get("points").size()).isEqualTo(2);
        assertThat(series.get("points").get(0).get("max").decimalValue()).isEqualByComparingTo("12.5");

        // 超过 2 小时没有数据，定时任务把设备标记为离线
        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .set(Device::getLastSeenAt, LocalDateTime.now().minusHours(3))
                .eq(Device::getId, deviceId));
        deviceStatusJob.markOffline();
        assertThat(deviceMapper.selectById(deviceId).getStatus()).isEqualTo("OFFLINE");
    }

    private JsonNode ingest(String code, String token, String body, ResultMatcher expected) throws Exception {
        String response = mvc.perform(post("/api/ingest/devices/" + code + "/readings")
                        .header("X-Device-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }

    private String loginAs(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, ResultMatcher expected) throws Exception {
        request.header("Authorization", "Bearer " + adminToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        String response = mvc.perform(request).andExpect(expected).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return response.isEmpty() ? null : json.readTree(response);
    }

    private static JsonNode find(JsonNode array, java.util.function.Predicate<JsonNode> predicate) {
        for (JsonNode n : array) {
            if (predicate.test(n)) {
                return n;
            }
        }
        throw new AssertionError("没有找到符合条件的元素");
    }
}
