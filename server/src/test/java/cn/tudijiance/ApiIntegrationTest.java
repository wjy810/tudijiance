package cn.tudijiance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
 * 用 H2（MySQL 模式）跑完整的接口流程。四级用户：
 * 系统管理员 admin、老师 teacher1、学生 student1、游客 guest1。
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

    private String admin;
    private String teacher;
    private String student;
    private String guest;

    @BeforeEach
    void loginAll() throws Exception {
        admin = loginAs("admin", "admin123");
        ensureUser("teacher1", "王老师", "TEACHER");
        ensureUser("student1", "张同学", "STUDENT");
        ensureUser("guest1", "大屏", "GUEST");
        teacher = loginAs("teacher1", "secret123");
        student = loginAs("student1", "secret123");
        guest = loginAs("guest1", "secret123");
    }

    @Test
    void rejectsRequestsWithoutToken() throws Exception {
        mvc.perform(get("/api/fields")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mobileLoginGetsALongerToken() throws Exception {
        JsonNode desktop = login(Map.of("username", "student1", "password", "secret123"));
        JsonNode mobile = login(Map.of("username", "student1", "password", "secret123", "client", "MOBILE"));
        Instant now = Instant.now();
        assertThat(Duration.between(now, Instant.parse(desktop.get("expiresAt").asString())).toHours()).isBetween(23L, 24L);
        assertThat(Duration.between(now, Instant.parse(mobile.get("expiresAt").asString())).toDays()).isBetween(29L, 30L);
        // 手机端的令牌一样能用
        call(mobile.get("token").asString(), get("/api/auth/me"), null, status().isOk());
    }

    private JsonNode login(Map<String, Object> body) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    @Test
    void permissionsFollowTheFourRoles() throws Exception {
        String fieldBody = "{\"name\":\"x\",\"rowCount\":1,\"colCount\":1}";
        // 系统管理员不能设置实验田，也不能录入数据
        mvc.perform(post("/api/fields").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(fieldBody)).andExpect(status().isForbidden());
        String entryBody = "{\"trialId\":1,\"traitId\":1,\"items\":[{\"plotId\":1,\"clientUuid\":\"x\"}]}";
        mvc.perform(post("/api/observations").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(entryBody)).andExpect(status().isForbidden());
        // 学生能看不能设置
        mvc.perform(post("/api/fields").header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON).content(fieldBody)).andExpect(status().isForbidden());
        mvc.perform(get("/api/fields").header("Authorization", "Bearer " + student)).andExpect(status().isOk());
        // 游客只能看大屏
        mvc.perform(get("/api/fields").header("Authorization", "Bearer " + guest)).andExpect(status().isForbidden());
        mvc.perform(get("/api/screen").header("Authorization", "Bearer " + guest)).andExpect(status().isOk());
        // 只有系统管理员能管理用户
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + teacher)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    }

    @Test
    void seedsStandardAndOperationTraits() throws Exception {
        JsonNode traits = call(teacher, get("/api/traits"), null, status().isOk());
        assertThat(traits.size()).isGreaterThan(90);
        JsonNode lodging = find(traits, t -> t.get("trait").get("code").asString().equals("lodging_final_grade"));
        assertThat(lodging.get("trait").get("aggMethod").asString()).isEqualTo("MAX");
        assertThat(lodging.get("options").get(2).get("description").asString()).contains("30°–45°");
        JsonNode fertilize = find(traits, t -> t.get("trait").get("code").asString().equals("op_fertilize"));
        assertThat(fertilize.get("trait").get("dataType").asString()).isEqualTo("OPERATION");
        assertThat(fertilize.get("options").get(0).get("label").asString()).isEqualTo("尿素");
    }

    @Test
    void fieldGridNumberingAndPlotKinds() throws Exception {
        JsonNode field = call(teacher, post("/api/fields"), Map.of("name", "东区试验田", "rowCount", 3, "colCount", 4,
                "plotLengthM", 6.65, "plotWidthM", 2.0), status().isOk());
        long fieldId = field.get("field").get("id").asLong();
        JsonNode plots = field.get("plots");
        assertThat(plots.size()).isEqualTo(12);
        assertThat(plots.get(0).get("code").asString()).isEqualTo("1");
        assertThat(plots.get(11).get("lengthM").decimalValue()).isEqualByComparingTo("6.65");

        long p1 = plots.get(0).get("id").asLong();
        long p2 = plots.get(1).get("id").asLong();
        JsonNode swapped = call(teacher, put("/api/fields/" + fieldId + "/plot-codes"),
                Map.of("codes", Map.of(String.valueOf(p1), "2", String.valueOf(p2), "1")), status().isOk());
        assertThat(swapped.get(0).get("code").asString()).isEqualTo("2");
        call(teacher, put("/api/fields/" + fieldId + "/plot-codes"), Map.of("codes", Map.of(String.valueOf(p1), "5")),
                status().isBadRequest());

        JsonNode patched = call(teacher, patch("/api/fields/" + fieldId + "/plots"),
                Map.of("plotIds", List.of(p1), "kind", "GUARD"), status().isOk());
        assertThat(patched.get(0).get("kind").asString()).isEqualTo("GUARD");

        JsonNode resized = call(teacher, put("/api/fields/" + fieldId),
                Map.of("name", "东区试验田", "rowCount", 4, "colCount", 4), status().isOk());
        assertThat(resized.get("plots").size()).isEqualTo(16);
    }

    @Test
    void trialLayoutWithControlLink() throws Exception {
        Setup s = setup("西区试验田");
        String trialUrl = "/api/trials/" + s.trialId;
        JsonNode layout = call(teacher, post(trialUrl + "/control-links"),
                Map.of("plotIds", List.of(s.plots.get(0), s.plots.get(1))), status().isOk());
        JsonNode link = layout.get("links").get(0);
        assertThat(link.get("label").asString()).isEqualTo("对照品种");
        assertThat(find(link.get("plots"), p -> p.get("control").asBoolean()).get("plotId").asLong())
                .isEqualTo(s.plots.get(0));
        // 两个小区都是品系 A，没有对照品种，又没指定对照
        call(teacher, post(trialUrl + "/control-links"), Map.of("plotIds", List.of(s.plots.get(1), s.plots.get(3))),
                status().isBadRequest());
        call(teacher, delete("/api/varieties/" + s.ckVariety), null, status().isConflict());
    }

    @Test
    void entryEditDeleteStatusAndStats() throws Exception {
        Setup s = setup("统计试验田");
        String trialId = String.valueOf(s.trialId);

        // 学生一次给 4 个小区录入施肥（选类型、填用量）
        JsonNode created = entry(student, s.trialId, "op_fertilize", s.plots.subList(0, 4),
                Map.of("valueText", "尿素", "valueNum", 10), status().isOk());
        assertThat(created.size()).isEqualTo(4);
        assertThat(created.get(0).get("displayValue").asString()).isEqualTo("尿素 10 kg/亩");
        assertThat(created.get(0).get("recordedBy").asString()).isEqualTo("张同学");

        // 同一个 clientUuid 重复提交，不会重复保存
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> once = entryBody(s.trialId, traitId("plant_height"),
                List.of(Map.of("plotId", s.plots.get(0), "clientUuid", uuid)), Map.of("valueNum", 78));
        long first = call(student, post("/api/observations"), once, status().isOk()).get(0).get("id").asLong();
        long again = call(student, post("/api/observations"), once, status().isOk()).get(0).get("id").asLong();
        assertThat(again).isEqualTo(first);

        // 分级只能选规定的级别；保护区不能录入
        entry(student, s.trialId, "lodging_final_grade", s.plots.subList(0, 1), Map.of("valueNum", 7),
                status().isBadRequest());
        entry(student, s.trialId, "plant_height", List.of(s.plots.get(5)), Map.of("valueNum", 70),
                status().isBadRequest());
        JsonNode grade = entry(student, s.trialId, "lodging_final_grade", s.plots.subList(0, 1),
                Map.of("valueNum", 3), status().isOk());
        assertThat(grade.get(0).get("displayValue").asString()).isEqualTo("3 级");

        // 小区产量：两个对照小区 10、11 kg，两个品系 A 小区 12、12.5 kg；小区面积 6.65 × 2 = 13.3 平方米
        double[] yields = {10, 12, 11, 12.5};
        for (int i = 0; i < 4; i++) {
            entry(student, s.trialId, "plot_yield", List.of(s.plots.get(i)), Map.of("valueNum", yields[i]),
                    status().isOk());
        }

        // 计算项目：全生育期 = 成熟期 − 播种期，生育期 = 成熟期 − 出苗期，折合亩产 = 小区产量 × 666.7 ÷ 面积
        entry(student, s.trialId, "sowing_date", s.plots.subList(0, 1),
                Map.of("valueDate", "2024-10-10", "observedOn", "2024-10-10"), status().isOk());
        entry(student, s.trialId, "emergence_date", s.plots.subList(0, 1),
                Map.of("valueDate", "2024-10-18", "observedOn", "2024-10-18"), status().isOk());
        entry(student, s.trialId, "maturity_date", s.plots.subList(0, 1),
                Map.of("valueDate", "2025-06-01", "observedOn", "2025-06-01"), status().isOk());
        JsonNode plot0 = call(student, get("/api/trials/" + s.trialId + "/plots/" + s.plots.get(0)), null,
                status().isOk());
        Map<String, String> computed = new HashMap<>();
        plot0.get("latest").forEach(v -> {
            if (v.get("computed").asBoolean()) {
                computed.put(v.get("traitName").asString(), v.get("displayValue").asString());
            }
        });
        assertThat(computed).containsEntry("全生育期", "234 d").containsEntry("生育期", "226 d")
                .containsEntry("折合亩产", "501.3 kg");
        JsonNode values = call(student, get("/api/trials/" + s.trialId + "/trait-values?traitId="
                + traitId("yield_per_mu")), null, status().isOk());
        assertThat(values.size()).isEqualTo(4);
        call(student, get("/api/trials/" + s.trialId + "/trait-values?traitId=" + traitId("op_fertilize")), null,
                status().isBadRequest());

        // 学生不能改，老师可以改；版本号不对时提示别人改过
        mvc.perform(put("/api/observations/" + first).header("Authorization", "Bearer " + student)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"valueNum\":80}"))
                .andExpect(status().isForbidden());
        JsonNode updated = call(teacher, put("/api/observations/" + first), Map.of("version", 1, "valueNum", 80),
                status().isOk());
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("updatedBy").asString()).isEqualTo("王老师");
        call(teacher, put("/api/observations/" + first), Map.of("version", 1, "valueNum", 81), status().isConflict());
        JsonNode history = call(student, get("/api/observations/" + first + "/history"), null, status().isOk());
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("action").asString()).isEqualTo("UPDATE");
        assertThat(history.get(0).get("oldValue").asString()).contains("78");
        assertThat(history.get(0).get("newValue").asString()).contains("80");

        JsonNode page = call(teacher, get("/api/observations?trialId=" + trialId + "&traitId=" + traitId("plant_height")),
                null, status().isOk());
        assertThat(page.get("total").asInt()).isEqualTo(1);
        call(teacher, delete("/api/observations/" + first), null, status().isOk());
        page = call(teacher, get("/api/observations?trialId=" + trialId + "&traitId=" + traitId("plant_height")),
                null, status().isOk());
        assertThat(page.get("total").asInt()).isZero();

        // 平面图状态：录了小区产量的算已收获；标记异常后显示有异常
        call(student, put("/api/trials/" + trialId + "/abnormal"),
                Map.of("plotIds", List.of(s.plots.get(1)), "abnormal", true, "note", "倒伏"), status().isOk());
        JsonNode st = call(teacher, get("/api/trials/" + trialId + "/status"), null, status().isOk());
        assertThat(statusOf(st, s.plots.get(0))).isEqualTo("HARVESTED");
        assertThat(statusOf(st, s.plots.get(1))).isEqualTo("ABNORMAL");
        assertThat(statusOf(st, s.plots.get(5))).isEqualTo("GUARD");
        assertThat(st.get("indicators").get("totalPlots").asInt()).isEqualTo(5);
        assertThat(st.get("indicators").get("abnormal").asInt()).isEqualTo(1);
        assertThat(st.get("indicators").get("enteredToday").asInt()).isEqualTo(4);

        // 右键小区详情：每个项目最近一次的值
        JsonNode detail = call(teacher, get("/api/trials/" + trialId + "/plots/" + s.plots.get(0)), null,
                status().isOk());
        assertThat(detail.get("variety").get("name").asString()).isEqualTo("周麦18-测试");
        assertThat(find(detail.get("latest"), l -> l.get("traitName").asString().equals("施肥"))
                .get("displayValue").asString()).isEqualTo("尿素 10 kg/亩");

        // 折合亩产 = 小区产量 × 666.7 ÷ 13.3；对照平均 (10+11)/2 → 526.3，品系 A (12+12.5)/2 → 614.1
        JsonNode summary = call(teacher, get("/api/stats/variety-summary?trialId=" + trialId + "&traitId="
                + traitId("yield_per_mu")), null, status().isOk());
        JsonNode top = summary.get("rows").get(0);
        assertThat(top.get("varietyName").asString()).isEqualTo("新品系A-测试");
        assertThat(top.get("value").decimalValue()).isEqualByComparingTo("614.1");
        assertThat(top.get("vsCkPercent").decimalValue()).isEqualByComparingTo("16.7");
        assertThat(summary.get("rows").get(1).get("value").decimalValue()).isEqualByComparingTo("526.3");

        JsonNode multi = call(teacher, get("/api/stats/multi-year?fieldId=" + s.fieldId + "&traitId="
                + traitId("yield_per_mu")), null, status().isOk());
        assertThat(multi.get("years").get(0).asInt()).isEqualTo(2026);
        assertThat(multi.get("series").size()).isEqualTo(2);

        // 品种主要性状汇总表：有折合亩产、全生育期两列，按亩产位次排序，品系 A 比对照 +16.7%
        JsonNode report = call(student, get("/api/stats/trial-report?trialId=" + s.trialId), null, status().isOk());
        List<String> columnCodes = new ArrayList<>();
        report.get("columns").forEach(c -> columnCodes.add(c.get("code").asString()));
        assertThat(columnCodes).contains("whole_growth_days", "yield_per_mu");
        assertThat(report.get("rows").get(0).get("varietyName").asString()).isEqualTo("新品系A-测试");
        assertThat(report.get("rows").get(0).get("yieldVsCkPercent").decimalValue()).isEqualByComparingTo("16.7");
        assertThat(report.get("rows").get(1).get("control").asBoolean()).isTrue();

        // 导出的 Excel 能用 POI 打开，表头和数据在
        byte[] xlsx = mvc.perform(get("/api/stats/trial-report/export?trialId=" + s.trialId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("品种");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("新品系A-测试");
        }
        byte[] data = mvc.perform(get("/api/observations/export?trialId=" + s.trialId + "&traitId="
                        + traitId("plot_yield")).header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2 + 4);
            assertThat(sheet.getRow(3).getCell(7).getNumericCellValue()).isEqualTo(10.0);
        }

        // 一次给两个小区录同一个值，大屏的最新录入合成一条
        entry(student, s.trialId, "op_weeding", s.plots.subList(0, 2), Map.of("valueText", "人工除草"),
                status().isOk());
        JsonNode screen = call(guest, get("/api/screen?fieldId=" + s.fieldId), null, status().isOk());
        assertThat(screen.get("fieldName").asString()).isEqualTo("统计试验田");
        assertThat(screen.get("plots").size()).isEqualTo(6);
        assertThat(screen.get("yieldRows").size()).isEqualTo(2);
        assertThat(screen.get("yieldYear").asInt()).isEqualTo(2026);
        assertThat(screen.get("recent").size()).isGreaterThan(0);
        assertThat(screen.get("recent").get(0).get("traitName").asString()).isEqualTo("中耕除草");
        assertThat(screen.get("recent").get(0).get("plotCount").asInt()).isEqualTo(2);

        // 下一年刚播种还没有产量：历年变化不列这一年，大屏的亩产对比仍显示 2026 年
        call(teacher, post("/api/trials"), Map.of("fieldId", s.fieldId, "seasonYear", 2027, "name", "2027 品比",
                "trialType", "COMPARISON"), status().isOk());
        multi = call(teacher, get("/api/stats/multi-year?fieldId=" + s.fieldId + "&traitId="
                + traitId("yield_per_mu")), null, status().isOk());
        assertThat(multi.get("years").size()).isEqualTo(1);
        screen = call(guest, get("/api/screen?fieldId=" + s.fieldId), null, status().isOk());
        assertThat(screen.get("seasonYear").asInt()).isEqualTo(2027);
        assertThat(screen.get("yieldYear").asInt()).isEqualTo(2026);
        assertThat(screen.get("yieldRows").size()).isEqualTo(2);
    }

    @Test
    void customTraitCanBeCreatedUsedAndProtected() throws Exception {
        Setup s = setup("自定义项目试验田");
        JsonNode custom = call(teacher, post("/api/traits"), Map.of("name", "田间积水", "dataType", "OPTION",
                "options", List.of("无", "轻", "重")), status().isOk());
        long traitId = custom.get("trait").get("id").asLong();
        assertThat(custom.get("trait").get("isStandard").asBoolean()).isFalse();
        assertThat(custom.get("options").size()).isEqualTo(3);
        call(student, post("/api/traits"), Map.of("name", "x", "dataType", "TEXT"), status().isForbidden());

        Map<String, Object> body = entryBody(s.trialId, traitId,
                List.of(Map.of("plotId", s.plots.get(0), "clientUuid", UUID.randomUUID().toString())),
                Map.of("valueText", "轻"));
        JsonNode saved = call(student, post("/api/observations"), body, status().isOk());
        assertThat(saved.get(0).get("displayValue").asString()).isEqualTo("轻");
        assertThat(saved.get(0).get("standardTrait").asBoolean()).isFalse();

        call(teacher, delete("/api/traits/" + traitId), null, status().isConflict());
        call(teacher, delete("/api/traits/" + traitId("plant_height")), null, status().isBadRequest());
    }

    @Test
    void deviceIngestOverHttp() throws Exception {
        JsonNode created = call(admin, post("/api/devices"), Map.of("code", "SOIL-T01", "name", "测试土壤传感器",
                "deviceType", "SOIL_3IN1", "depthCm", 10), status().isOk());
        String token = created.get("token").asString();
        long deviceId = created.get("device").get("id").asLong();
        assertThat(created.get("device").get("mqttTopic").asString()).isEqualTo("tudi/devices/SOIL-T01/up");

        String payload = """
                {"ts": "2026-10-30T08:00:00+08:00",
                 "values": {"soil_temp": 12.3, "soil_moisture": "23.5", "soil_ph": 7.12, "air_temp": 15}}
                """;
        JsonNode result = ingest("SOIL-T01", token, payload, status().isOk());
        assertThat(result.get("accepted").asInt()).isEqualTo(3);
        assertThat(result.get("rejected").get(0).asString()).contains("air_temp").contains("未启用");
        result = ingest("SOIL-T01", token, payload, status().isOk());
        assertThat(result.get("duplicates").asInt()).isEqualTo(3);
        result = ingest("SOIL-T01", token, """
                {"records": [
                  {"ts": "2026-10-30T08:30:00", "values": {"soil_ph": 12.5}},
                  {"ts": "2026-10-30T09:00:00", "values": {"soil_ph": 7.10}}]}
                """, status().isOk());
        assertThat(result.get("accepted").asInt()).isEqualTo(2);
        ingest("SOIL-T01", "wrong-token", payload, status().isUnauthorized());
        ingest("SOIL-T01", token, "not json", status().isBadRequest());

        JsonNode device = find(call(admin, get("/api/devices"), null, status().isOk()),
                d -> d.get("code").asString().equals("SOIL-T01"));
        assertThat(device.get("status").asString()).isEqualTo("ONLINE");
        assertThat(device.get("latest").get("soil_ph").get("value").decimalValue()).isEqualByComparingTo("7.10");

        JsonNode series = call(admin, get("/api/devices/" + deviceId + "/readings?metric=soil_ph&bucket=HOUR"
                + "&from=2026-10-30T00:00:00&to=2026-10-30T23:59:59"), null, status().isOk());
        assertThat(series.get("points").size()).isEqualTo(2);
        assertThat(series.get("points").get(0).get("max").decimalValue()).isEqualByComparingTo("12.5");

        deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .set(Device::getLastSeenAt, LocalDateTime.now().minusHours(3))
                .eq(Device::getId, deviceId));
        deviceStatusJob.markOffline();
        assertThat(deviceMapper.selectById(deviceId).getStatus()).isEqualTo("OFFLINE");
    }

    // ---------- 准备数据 ----------

    /** 2 行 × 3 列；第 1、3 个小区种对照，第 2、4 个种品系 A，第 5 个空着，第 6 个是保护区。 */
    @Test
    void importHistoricalSpreadsheet() throws Exception {
        Setup s = setup("导入试验田");
        // 表格第 1 行是标题，第 2 行是表头；小区编号是 1~6，第 6 个是保护区
        byte[] xlsx = workbook(List.of(
                List.of("2026 年品比试验记载表"),
                List.of("小区编号", "品种", "重复", "播种期", "株高(cm)", "最终倒伏程度", "折合亩产"),
                List.of(1, "周麦18-测试", "I", "10月12日", 78.5, "2级", 500),
                List.of(2, "新品系A-测试", 1, "2025-10-12", "80cm", 3, 520),
                List.of(3, "新品种B-导入", "II", "10.12", "abc", "9级", ""),
                List.of(99, "周麦18-测试", "I", "10月12日", 70),
                List.of(6, "周麦18-测试", "I", "10月12日", 70)));
        // 选中 A2:G7，每列一个字段
        Map<String, Object> mapping = mapping(s.trialId, range(1, 0, 6, 6), "COLUMNS", List.of(
                field(0, "PLOT_CODE", null), field(1, "VARIETY", null), field(2, "REPLICATE", null),
                field(3, "TRAIT", traitId("sowing_date")), field(4, "TRAIT", traitId("plant_height")),
                field(5, "TRAIT", traitId("lodging_final_grade")), field(6, "TRAIT", traitId("yield_per_mu"))));
        mapping.put("observedOn", "2026-06-10");
        mapping.put("assignLayout", true);
        mapping.put("createVarieties", true);

        // 学生不能导入
        mvc.perform(multipart("/api/imports/run").file(file(xlsx)).file(part(mapping))
                .header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());

        JsonNode check = importRun(xlsx, mapping);
        assertThat(check.get("dryRun").asBoolean()).isTrue();
        assertThat(check.get("records").asInt()).isEqualTo(5);
        assertThat(check.get("created").asInt()).isEqualTo(9);
        assertThat(check.get("errorCount").asInt()).isEqualTo(4);
        assertThat(check.get("newVarieties").get(0).asString()).isEqualTo("新品种B-导入");
        List<String> where = new ArrayList<>();
        check.get("errors").forEach(e -> where.add(e.get("row").asInt() + e.get("col").asString()));
        assertThat(where).containsExactly("5E", "5F", "6A", "7A");
        // 出错的格子带工作表里的行列号（从 0 开始），页面上用来定位
        assertThat(check.get("errors").get(0).get("r").asInt()).isEqualTo(4);
        assertThat(check.get("errors").get(0).get("c").asInt()).isEqualTo(4);
        // 检查时什么都不写
        assertThat(call(teacher, get("/api/observations?trialId=" + s.trialId), null, status().isOk())
                .get("total").asInt()).isZero();

        // 在页面上把 E5 的 "abc" 改成 81，F5 的 "9级" 清空
        mapping.put("edits", List.of(Map.of("row", 4, "col", 4, "value", "81"), Map.of("row", 4, "col", 5, "value", "")));
        JsonNode fixed = importRun(xlsx, mapping);
        assertThat(fixed.get("errorCount").asInt()).isEqualTo(2);
        assertThat(fixed.get("created").asInt()).isEqualTo(10);

        mapping.put("dryRun", false);
        mapping.put("rememberHeaders", true);
        JsonNode done = importRun(xlsx, mapping);
        assertThat(done.get("created").asInt()).isEqualTo(10);
        assertThat(done.get("aliasesRemembered").asInt()).isEqualTo(4);
        JsonNode yields = call(teacher, get("/api/observations?trialId=" + s.trialId + "&traitId="
                + traitId("plot_yield") + "&plotId=" + s.plots.get(0)), null, status().isOk());
        // 亩产 500 × 面积 13.3 ÷ 666.7 = 9.97 kg
        assertThat(yields.get("items").get(0).get("valueNum").decimalValue()).isEqualByComparingTo("9.97");
        assertThat(yields.get("items").get(0).get("source").asString()).isEqualTo("IMPORT");
        assertThat(yields.get("items").get(0).get("observedOn").asString()).isEqualTo("2026-06-10");
        // 第 3 个小区按表格改成了新品种 B 的重复 II
        JsonNode layout = call(teacher, get("/api/trials/" + s.trialId + "/layout"), null, status().isOk());
        JsonNode plot3 = find(layout.get("plantings"), p -> p.get("plotId").asLong() == s.plots.get(2));
        assertThat(plot3.get("replicateNo").asInt()).isEqualTo(2);
        assertThat(plot3.get("varietyId").asLong()).isNotEqualTo(s.ckVariety);
        // 表头记成了录入项目的别名（规范化后的写法）
        JsonNode height = find(call(teacher, get("/api/traits"), null, status().isOk()),
                t -> t.get("trait").get("code").asString().equals("plant_height"));
        assertThat(height.get("aliases").toString()).contains("株高(cm)");

        // 同一份表格再导一次不会重复
        JsonNode again = importRun(xlsx, mapping);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(10);

        // 每行一个字段：第一列是表头，右边每列是一条记录；没有小区编号时按品种 + 重复找小区
        byte[] byRows = workbook(List.of(
                List.of("品种", "周麦18-测试", "新品系A-测试"),
                List.of("重复", "I", "I"),
                List.of("千粒重", 45.2, 47.1)));
        Map<String, Object> m2 = mapping(s.trialId, range(0, 0, 2, 2), "ROWS", List.of(
                field(0, "VARIETY", null), field(1, "REPLICATE", null), field(2, "TRAIT", traitId("thousand_grain_weight"))));
        m2.put("observedOn", "2026-06-20");
        m2.put("dryRun", false);
        JsonNode rowsResult = importRun(byRows, m2);
        assertThat(rowsResult.get("records").asInt()).isEqualTo(2);
        assertThat(rowsResult.get("created").asInt()).isEqualTo(2);

        // 范围超出表格、字段重复都要报错
        Map<String, Object> bad = mapping(s.trialId, range(0, 0, 9, 2), "ROWS", List.of(field(0, "VARIETY", null),
                field(2, "TRAIT", traitId("thousand_grain_weight"))));
        importExpect(byRows, bad, status().isBadRequest());
        Map<String, Object> twice = mapping(s.trialId, range(0, 0, 2, 2), "ROWS", List.of(field(0, "VARIETY", null),
                field(0, "TRAIT", traitId("thousand_grain_weight"))));
        importExpect(byRows, twice, status().isBadRequest());

        // 预览给出整张表；模板
        JsonNode preview = json.readTree(mvc.perform(multipart("/api/imports/preview").file(file(xlsx))
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(preview.get("sheets").get(0).get("rows").get(1).get(0).asString()).isEqualTo("小区编号");
        assertThat(preview.get("sheets").get(0).get("rows").size()).isEqualTo(7);
        byte[] template = mvc.perform(get("/api/imports/template?trialId=" + s.trialId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("小区编号");
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(5);
        }
    }

    @Test
    void traitAliasesAreEditableAndUnique() throws Exception {
        long height = traitId("plant_height");
        long spikes = traitId("effective_spikes");
        JsonNode saved = call(teacher, put("/api/traits/" + height + "/aliases"),
                Map.of("aliases", List.of("高度 （CM）", "高度(cm)", " ")), status().isOk());
        // 全角转半角、去空白、小写，重复的合并
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.get(0).asString()).isEqualTo("高度(cm)");
        // 同一个写法不能给两个项目
        call(teacher, put("/api/traits/" + spikes + "/aliases"), Map.of("aliases", List.of("高度(cm)")), status().isConflict());
        call(student, put("/api/traits/" + spikes + "/aliases"), Map.of("aliases", List.of("穗数")), status().isForbidden());
        // 新建自定义项目时可以一起记下表头
        JsonNode custom = call(teacher, post("/api/traits"), Map.of("name", "穗长-别名测试", "dataType", "NUMBER",
                "unit", "cm", "aliases", List.of("穗长（cm）")), status().isOk());
        assertThat(custom.get("aliases").get(0).asString()).isEqualTo("穗长(cm)");
        call(teacher, put("/api/traits/" + height + "/aliases"), Map.of("aliases", List.of()), status().isOk());
    }

    @Test
    void selfServiceAnalysisAndSavedCharts() throws Exception {
        Setup s = setup("分析试验田");
        String url = "/api/trials/" + s.trialId + "/plantings";
        call(teacher, put(url), Map.of("plotIds", s.plots.subList(0, 2), "attribute", "REPLICATE", "value", 1), status().isOk());
        call(teacher, put(url), Map.of("plotIds", s.plots.subList(2, 4), "attribute", "REPLICATE", "value", 2), status().isOk());
        // 对照（第 0、2 个小区）10、11 kg，品系 A（第 1、3 个小区）都是 12 kg；小区面积 13.3 平方米
        double[] yields = {10, 12, 11, 12};
        for (int i = 0; i < 4; i++) {
            entry(student, s.trialId, "plot_yield", List.of(s.plots.get(i)), Map.of("valueNum", yields[i]), status().isOk());
        }
        entry(student, s.trialId, "lodging_final_grade", s.plots.subList(0, 2), Map.of("valueNum", 1), status().isOk());
        entry(student, s.trialId, "lodging_final_grade", List.of(s.plots.get(2)), Map.of("valueNum", 3), status().isOk());

        // 按品种：折合亩产平均、小区数、小区产量的变异系数
        JsonNode byVariety = analysis(student, Map.of("fieldId", s.fieldId,
                "dimensions", List.of(Map.of("type", "VARIETY")),
                "measures", List.of(Map.of("traitId", traitId("yield_per_mu"), "agg", "AVG"),
                        Map.of("agg", "COUNT"), Map.of("traitId", traitId("plot_yield"), "agg", "CV"))), status().isOk());
        assertThat(byVariety.get("columns").get(1).get("label").asString()).isEqualTo("折合亩产 · 平均");
        assertThat(byVariety.get("records").asInt()).isEqualTo(4);
        JsonNode ck = find(byVariety.get("rows"), r -> r.get(0).asString().equals("周麦18-测试"));
        // (501.278 + 551.406) ÷ 2，和"按品种汇总"的结果一致
        assertThat(ck.get(1).decimalValue()).isEqualByComparingTo("526.3");
        assertThat(ck.get(2).asInt()).isEqualTo(2);
        assertThat(ck.get(3).decimalValue()).isEqualByComparingTo("6.7");
        JsonNode lineA = find(byVariety.get("rows"), r -> r.get(0).asString().equals("新品系A-测试"));
        assertThat(lineA.get(1).decimalValue()).isEqualByComparingTo("601.5");

        // 按分级项目的取值分组，数小区
        JsonNode byGrade = analysis(teacher, Map.of("fieldId", s.fieldId,
                "dimensions", List.of(Map.of("type", "TRAIT", "traitId", traitId("lodging_final_grade"))),
                "measures", List.of(Map.of("agg", "COUNT"))), status().isOk());
        assertThat(byGrade.get("rows").size()).isEqualTo(2);
        assertThat(byGrade.get("rows").get(0).get(0).asString()).isEqualTo("1 级");
        assertThat(byGrade.get("rows").get(0).get(1).asInt()).isEqualTo(2);

        // 不分组：每个小区一行；按品种、重复筛选
        JsonNode detail = analysis(teacher, Map.of("fieldId", s.fieldId, "dimensions", List.of(),
                "measures", List.of(Map.of("traitId", traitId("plot_yield"), "agg", "AVG"))), status().isOk());
        assertThat(detail.get("rows").size()).isEqualTo(4);
        JsonNode filtered = analysis(teacher, Map.of("fieldId", s.fieldId, "varietyIds", List.of(s.ckVariety),
                "replicates", List.of(2), "dimensions", List.of(Map.of("type", "REPLICATE")),
                "measures", List.of(Map.of("traitId", traitId("plot_yield"), "agg", "MAX"))), status().isOk());
        assertThat(filtered.get("rows").size()).isEqualTo(1);
        assertThat(filtered.get("rows").get(0).get(0).asString()).isEqualTo("重复 II");
        assertThat(filtered.get("rows").get(0).get(1).decimalValue()).isEqualByComparingTo("11");

        // 文字、日期类项目不能求平均；数值项目不能用来分组；游客不能用
        analysis(teacher, Map.of("dimensions", List.of(), "measures",
                List.of(Map.of("traitId", traitId("sowing_date"), "agg", "AVG"))), status().isBadRequest());
        analysis(teacher, Map.of("dimensions", List.of(Map.of("type", "TRAIT", "traitId", traitId("plant_height"))),
                "measures", List.of(Map.of("agg", "COUNT"))), status().isBadRequest());
        analysis(guest, Map.of("dimensions", List.of(), "measures", List.of(Map.of("agg", "COUNT"))), status().isForbidden());

        // 导出 Excel
        byte[] xlsx = mvc.perform(post("/api/analysis/export").header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fieldId", s.fieldId,
                                "dimensions", List.of(Map.of("type", "VARIETY")),
                                "measures", List.of(Map.of("traitId", traitId("yield_per_mu"), "agg", "AVG"))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("品种");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("折合亩产 · 平均（kg）");
        }

        // 保存图表：学生建的，老师可以改；学生不能删老师的
        JsonNode chart = call(student, post("/api/analysis/charts"), Map.of("name", "各品种亩产",
                "config", Map.of("chartType", "bar", "fieldId", s.fieldId)), status().isOk());
        long chartId = chart.get("id").asLong();
        assertThat(chart.get("config").get("chartType").asString()).isEqualTo("bar");
        call(teacher, put("/api/analysis/charts/" + chartId), Map.of("name", "各品种亩产（改）",
                "config", Map.of("chartType", "line")), status().isOk());
        JsonNode teacherChart = call(teacher, post("/api/analysis/charts"), Map.of("name", "老师的图",
                "config", Map.of("chartType", "pie")), status().isOk());
        call(student, delete("/api/analysis/charts/" + teacherChart.get("id").asLong()), null, status().isForbidden());
        call(teacher, post("/api/analysis/charts"), Map.of("name", "格式不对", "config", List.of(1, 2)), status().isBadRequest());
        JsonNode list = call(student, get("/api/analysis/charts"), null, status().isOk());
        JsonNode mine = find(list, c -> c.get("id").asLong() == chartId);
        assertThat(mine.get("name").asString()).isEqualTo("各品种亩产（改）");
        assertThat(mine.get("createdByName").asString()).isEqualTo("张同学");
        call(student, delete("/api/analysis/charts/" + chartId), null, status().isOk());
        call(teacher, delete("/api/analysis/charts/" + teacherChart.get("id").asLong()), null, status().isOk());
    }

    private JsonNode analysis(String token, Map<String, Object> query, ResultMatcher expected) throws Exception {
        return call(token, post("/api/analysis/query"), query, expected);
    }

    private static Map<String, Object> range(int top, int left, int bottom, int right) {
        return Map.of("top", top, "left", left, "bottom", bottom, "right", right);
    }

    private static Map<String, Object> field(int index, String role, Long traitId) {
        Map<String, Object> f = new HashMap<>(Map.of("index", index, "role", role));
        if (traitId != null) {
            f.put("traitId", traitId);
        }
        return f;
    }

    private static Map<String, Object> mapping(long trialId, Map<String, Object> range, String orientation,
                                               List<Map<String, Object>> fields) {
        return new HashMap<>(Map.of("trialId", trialId, "sheet", 0, "range", range, "orientation", orientation,
                "fields", fields, "assignLayout", false, "createVarieties", false, "rememberHeaders", false,
                "dryRun", true));
    }

    private void importExpect(byte[] xlsx, Map<String, Object> mapping, ResultMatcher expected) throws Exception {
        mvc.perform(multipart("/api/imports/run").file(file(xlsx)).file(part(mapping))
                .header("Authorization", "Bearer " + teacher)).andExpect(expected);
    }

    private JsonNode importRun(byte[] xlsx, Map<String, Object> mapping) throws Exception {
        String body = mvc.perform(multipart("/api/imports/run").file(file(xlsx)).file(part(mapping))
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "历史数据.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private MockMultipartFile part(Map<String, Object> mapping) {
        return new MockMultipartFile("mapping", "", MediaType.APPLICATION_JSON_VALUE,
                json.writeValueAsString(mapping).getBytes(StandardCharsets.UTF_8));
    }

    /** 用 POI 写一个测试用的工作簿：数字写成数字格子，"2025-10-12" 这样的字符串照原样写 */
    private static byte[] workbook(List<List<Object>> rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("记载表");
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    Object v = rows.get(r).get(c);
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (!"".equals(v)) {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private record Setup(long fieldId, long trialId, long ckVariety, long testVariety, List<Long> plots) {
    }

    private Setup setup(String fieldName) throws Exception {
        JsonNode field = call(teacher, post("/api/fields"), Map.of("name", fieldName, "rowCount", 2, "colCount", 3,
                "plotLengthM", 6.65, "plotWidthM", 2.0), status().isOk());
        long fieldId = field.get("field").get("id").asLong();
        List<Long> plots = new ArrayList<>();
        field.get("plots").forEach(p -> plots.add(p.get("id").asLong()));
        long ck = varietyId("周麦18-测试", "YIELD");
        long test = varietyId("新品系A-测试", null);
        long trialId = call(teacher, post("/api/trials"), Map.of("fieldId", fieldId, "seasonYear", 2026,
                "name", "2026 品比试验", "trialType", "COMPARISON", "wheatZone", "Q3"), status().isOk())
                .get("id").asLong();
        String url = "/api/trials/" + trialId + "/plantings";
        call(teacher, put(url), Map.of("plotIds", List.of(plots.get(0), plots.get(2)), "attribute", "VARIETY",
                "value", ck), status().isOk());
        call(teacher, put(url), Map.of("plotIds", List.of(plots.get(1), plots.get(3)), "attribute", "VARIETY",
                "value", test), status().isOk());
        call(teacher, patch("/api/fields/" + fieldId + "/plots"), Map.of("plotIds", List.of(plots.get(5)),
                "kind", "GUARD"), status().isOk());
        return new Setup(fieldId, trialId, ck, test, plots);
    }

    private long varietyId(String name, String controlType) throws Exception {
        JsonNode existing = call(teacher, get("/api/varieties"), null, status().isOk());
        for (JsonNode v : existing) {
            if (v.get("name").asString().equals(name)) {
                return v.get("id").asLong();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("name", name));
        if (controlType != null) {
            body.put("controlType", controlType);
        }
        return call(teacher, post("/api/varieties"), body, status().isOk()).get("id").asLong();
    }

    private long traitId(String code) throws Exception {
        return find(call(teacher, get("/api/traits"), null, status().isOk()),
                t -> t.get("trait").get("code").asString().equals(code)).get("trait").get("id").asLong();
    }

    private JsonNode entry(String token, long trialId, String traitCode, List<Long> plotIds,
                           Map<String, Object> value, ResultMatcher expected) throws Exception {
        List<Map<String, Object>> items = plotIds.stream()
                .map(id -> Map.<String, Object>of("plotId", id, "clientUuid", UUID.randomUUID().toString())).toList();
        return call(token, post("/api/observations"), entryBody(trialId, traitId(traitCode), items, value), expected);
    }

    private static Map<String, Object> entryBody(long trialId, long traitId, List<Map<String, Object>> items,
                                                 Map<String, Object> value) {
        Map<String, Object> body = new HashMap<>(value);
        body.put("trialId", trialId);
        body.put("traitId", traitId);
        body.put("items", items);
        return body;
    }

    private static String statusOf(JsonNode trialStatus, long plotId) {
        return find(trialStatus.get("plots"), p -> p.get("plotId").asLong() == plotId).get("status").asString();
    }

    private void ensureUser(String username, String displayName, String role) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "displayName", displayName,
                "role", role, "password", "secret123"));
        int code = mvc.perform(post("/api/users").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 409);
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
