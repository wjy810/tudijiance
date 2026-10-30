package cn.tudijiance.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.common.AppProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 解析 JSON 报文，支持两种写法：
 *
 * <pre>
 * 单条：{"ts": 1730275200, "values": {"soil_temp": 12.3, "soil_moisture": 23.5, "soil_ph": 7.12}}
 * 多条：{"records": [{"ts": ..., "values": {...}}, ...]}   （DTU 断线后补发缓存数据时用）
 * </pre>
 *
 * ts 可以是秒或毫秒时间戳，也可以是 ISO 8601 时间字符串；不带时区的按服务器配置的时区处理；省略时用收到的时间。
 */
@Component
public class JsonPayloadDecoder implements PayloadDecoder {

    private static final int MAX_RECORDS = 1000;

    private final JsonMapper jsonMapper;
    private final ZoneId zone;

    public JsonPayloadDecoder(JsonMapper jsonMapper, AppProperties properties) {
        this.jsonMapper = jsonMapper;
        this.zone = properties.zoneId();
    }

    @Override
    public List<ReadingRecord> decode(byte[] payload) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(payload);
        } catch (JacksonException e) {
            throw ApiException.badRequest("报文不是合法的 JSON");
        }
        if (root == null || !root.isObject()) {
            throw ApiException.badRequest("报文必须是 JSON 对象");
        }
        List<ReadingRecord> records = new ArrayList<>();
        if (root.has("records")) {
            JsonNode list = root.get("records");
            if (!list.isArray()) {
                throw ApiException.badRequest("records 必须是数组");
            }
            if (list.size() > MAX_RECORDS) {
                throw ApiException.badRequest("一次最多上报 " + MAX_RECORDS + " 条");
            }
            for (JsonNode item : list) {
                records.add(record(item));
            }
        } else {
            records.add(record(root));
        }
        return records;
    }

    private ReadingRecord record(JsonNode node) {
        JsonNode values = node.get("values");
        if (values == null || !values.isObject() || values.isEmpty()) {
            throw ApiException.badRequest("缺少 values，或 values 为空");
        }
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : values.properties()) {
            JsonNode v = e.getValue();
            BigDecimal number;
            if (v.isNumber()) {
                number = v.decimalValue();
            } else if (v.isString()) {
                try {
                    number = new BigDecimal(v.asString().trim());
                } catch (NumberFormatException ex) {
                    throw ApiException.badRequest(e.getKey() + " 的值不是数字");
                }
            } else {
                throw ApiException.badRequest(e.getKey() + " 的值不是数字");
            }
            map.put(e.getKey(), number);
        }
        return new ReadingRecord(parseTime(node.get("ts")), map);
    }

    private LocalDateTime parseTime(JsonNode ts) {
        if (ts == null || ts.isNull()) {
            return null;
        }
        if (ts.isNumber()) {
            long n = ts.asLong();
            Instant instant = n > 100_000_000_000L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
            return LocalDateTime.ofInstant(instant, zone);
        }
        String s = ts.asString().trim();
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 不带时区的时间，按服务器时区处理
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("ts 时间格式不正确：" + s);
        }
    }
}
