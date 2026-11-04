package cn.tudijiance.trial;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.trial.TrialDtos.TrialBookRequest;
import cn.tudijiance.trial.TrialDtos.TrialBookView;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TrialBookService {

    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {
    };

    private final TrialBookMapper bookMapper;
    private final TrialMapper trialMapper;
    private final TrialService trialService;
    private final UserMapper userMapper;
    private final JsonMapper json;
    private final Clock clock;

    public TrialBookService(TrialBookMapper bookMapper, TrialMapper trialMapper, TrialService trialService,
                            UserMapper userMapper, JsonMapper json, Clock clock) {
        this.bookMapper = bookMapper;
        this.trialMapper = trialMapper;
        this.trialService = trialService;
        this.userMapper = userMapper;
        this.json = json;
        this.clock = clock;
    }

    public TrialBookView get(Long trialId) {
        Trial trial = trialService.get(trialId);
        TrialBook book = bookMapper.selectById(trialId);
        if (book == null) {
            return new TrialBookView(trialId, Map.of(), Map.of(), Map.of(), trial.getHarvestAreaM2(), null, null);
        }
        String updatedBy = null;
        if (book.getUpdatedBy() != null) {
            User u = userMapper.selectById(book.getUpdatedBy());
            updatedBy = u == null ? null : (u.getDisplayName() == null ? u.getUsername() : u.getDisplayName());
        }
        return new TrialBookView(trialId, read(book.getInfo()), read(book.getWeather()), read(book.getEvaluations()),
                trial.getHarvestAreaM2(), book.getUpdatedAt(), updatedBy);
    }

    /** 整份保存。产量计算参数齐了时算出收获面积，记到试验上，折合亩产按它算 */
    @Transactional
    public TrialBookView save(Long trialId, TrialBookRequest req, long userId) {
        Trial trial = trialService.get(trialId);
        Map<String, String> info = clean(req.info(), 300, 4000);
        Map<String, String> weather = clean(req.weather(), 2000, 32);
        Map<String, String> evaluations = clean(req.evaluations(), 500, 4000);

        TrialBook book = bookMapper.selectById(trialId);
        boolean fresh = book == null;
        if (fresh) {
            book = new TrialBook();
            book.setTrialId(trialId);
        }
        book.setInfo(json.writeValueAsString(info));
        book.setWeather(json.writeValueAsString(weather));
        book.setEvaluations(json.writeValueAsString(evaluations));
        book.setUpdatedBy(userId);
        book.setUpdatedAt(LocalDateTime.now(clock));
        if (fresh) {
            bookMapper.insert(book);
        } else {
            bookMapper.updateById(book);
        }

        BigDecimal area = HarvestArea.of(info);
        trialMapper.update(null, Wrappers.<Trial>lambdaUpdate()
                .set(Trial::getHarvestAreaM2, area)
                .eq(Trial::getId, trial.getId()));
        return get(trialId);
    }

    private Map<String, String> read(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return json.readValue(text, MAP);
    }

    /** 去掉空值；限制条数和长度，免得一份记载本无限变大 */
    private static Map<String, String> clean(Map<String, String> in, int maxEntries, int maxLength) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, String> e : in.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().strip();
            String value = e.getValue() == null ? "" : e.getValue().strip();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (key.length() > 64) {
                throw ApiException.badRequest("字段名太长：" + key.substring(0, 20) + "…");
            }
            if (value.length() > maxLength) {
                throw ApiException.badRequest("内容太长，不能超过 " + maxLength + " 个字");
            }
            out.put(key, value);
        }
        if (out.size() > maxEntries) {
            throw ApiException.badRequest("填写的内容太多，不能超过 " + maxEntries + " 项");
        }
        return out;
    }
}
