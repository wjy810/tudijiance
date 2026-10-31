package cn.tudijiance.analysis;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.analysis.AnalysisDtos.AnalysisQuery;
import cn.tudijiance.analysis.AnalysisDtos.AnalysisResult;
import cn.tudijiance.analysis.AnalysisDtos.ChartRequest;
import cn.tudijiance.analysis.AnalysisDtos.ChartView;
import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 自助分析：自己选范围、分组和统计项目做图表；图表可以保存下来。老师和学生都能用。 */
@RestController
@RequestMapping("/api/analysis")
@PreAuthorize(Roles.CAN_RECORD)
public class AnalysisController {

    private final AnalysisService analysisService;
    private final ChartService chartService;

    public AnalysisController(AnalysisService analysisService, ChartService chartService) {
        this.analysisService = analysisService;
        this.chartService = chartService;
    }

    @PostMapping("/query")
    public AnalysisResult query(@Valid @RequestBody AnalysisQuery query) {
        return analysisService.query(query);
    }

    /** 把查询结果导出成 Excel */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody AnalysisQuery query) {
        AnalysisResult result = analysisService.query(query);
        byte[] xlsx = ExcelExport.write("分析结果", List.of(), AnalysisService.headers(result), result.rows());
        return ExcelExport.response("分析结果.xlsx", xlsx);
    }

    @GetMapping("/charts")
    public List<ChartView> charts() {
        return chartService.list();
    }

    @PostMapping("/charts")
    public ChartView createChart(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChartRequest req) {
        return chartService.create(req, CurrentUser.id(jwt));
    }

    @PutMapping("/charts/{id}")
    public ChartView updateChart(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                 @Valid @RequestBody ChartRequest req) {
        return chartService.update(id, req, CurrentUser.id(jwt), CurrentUser.role(jwt));
    }

    @DeleteMapping("/charts/{id}")
    public void deleteChart(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        chartService.delete(id, CurrentUser.id(jwt), CurrentUser.role(jwt));
    }
}
