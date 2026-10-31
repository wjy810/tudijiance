package cn.tudijiance.analysis;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.analysis.AnalysisDtos.ChartRequest;
import cn.tudijiance.analysis.AnalysisDtos.ChartView;
import cn.tudijiance.common.ApiException;
import cn.tudijiance.security.Roles;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 自助分析里保存的图表。大家都能看；改名、修改、删除只能是建的人或者老师。 */
@Service
public class ChartService {

    static final int MAX_CONFIG_LENGTH = 20_000;

    private final AnalysisChartMapper chartMapper;
    private final UserMapper userMapper;
    private final JsonMapper json;
    private final Clock clock;

    public ChartService(AnalysisChartMapper chartMapper, UserMapper userMapper, JsonMapper json, Clock clock) {
        this.chartMapper = chartMapper;
        this.userMapper = userMapper;
        this.json = json;
        this.clock = clock;
    }

    public List<ChartView> list() {
        List<AnalysisChart> charts = chartMapper.selectList(Wrappers.<AnalysisChart>lambdaQuery()
                .orderByDesc(AnalysisChart::getUpdatedAt));
        Map<Long, String> names = charts.isEmpty() ? Map.of()
                : userMapper.selectByIds(charts.stream().map(AnalysisChart::getCreatedBy).distinct().toList()).stream()
                        .collect(Collectors.toMap(User::getId, u -> u.getDisplayName() == null ? u.getUsername() : u.getDisplayName()));
        return charts.stream().map(c -> view(c, names.get(c.getCreatedBy()))).toList();
    }

    public ChartView create(ChartRequest req, long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        AnalysisChart chart = new AnalysisChart();
        chart.setName(req.name().trim());
        chart.setConfig(configText(req.config()));
        chart.setCreatedBy(userId);
        chart.setCreatedAt(now);
        chart.setUpdatedAt(now);
        chartMapper.insert(chart);
        return view(chart, null);
    }

    public ChartView update(Long id, ChartRequest req, long userId, String role) {
        AnalysisChart chart = editable(id, userId, role);
        chart.setName(req.name().trim());
        chart.setConfig(configText(req.config()));
        chart.setUpdatedAt(LocalDateTime.now(clock));
        chartMapper.updateById(chart);
        return view(chart, null);
    }

    public void delete(Long id, long userId, String role) {
        chartMapper.deleteById(editable(id, userId, role).getId());
    }

    private AnalysisChart editable(Long id, long userId, String role) {
        AnalysisChart chart = chartMapper.selectById(id);
        if (chart == null) {
            throw ApiException.notFound("图表");
        }
        if (chart.getCreatedBy() != userId && !Roles.TEACHER.equals(role)) {
            throw ApiException.forbidden("只能修改自己保存的图表");
        }
        return chart;
    }

    private String configText(JsonNode config) {
        if (!config.isObject()) {
            throw ApiException.badRequest("图表设置格式不正确");
        }
        String text = json.writeValueAsString(config);
        if (text.length() > MAX_CONFIG_LENGTH) {
            throw ApiException.badRequest("图表设置太长");
        }
        return text;
    }

    private ChartView view(AnalysisChart c, String createdByName) {
        return new ChartView(c.getId(), c.getName(), json.readTree(c.getConfig()), c.getCreatedBy(), createdByName,
                c.getUpdatedAt());
    }
}
