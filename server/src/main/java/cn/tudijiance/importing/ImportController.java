package cn.tudijiance.importing;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.common.ExcelExport;
import cn.tudijiance.importing.ImportDtos.ImportMapping;
import cn.tudijiance.importing.ImportDtos.ImportResult;
import cn.tudijiance.importing.ImportDtos.Preview;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;
import cn.tudijiance.trial.TrialService;
import jakarta.validation.Valid;

/** 导入历史电子表格（老师）：先预览，再对应列、检查，最后导入。 */
@RestController
@RequestMapping("/api/imports")
@PreAuthorize(Roles.IS_TEACHER)
public class ImportController {

    private final ImportService importService;
    private final TrialService trialService;

    public ImportController(ImportService importService, TrialService trialService) {
        this.importService = importService;
        this.trialService = trialService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Preview preview(@RequestPart("file") MultipartFile file) {
        return importService.preview(bytes(file), file.getOriginalFilename());
    }

    @PostMapping(value = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult run(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
                            @Valid @RequestPart("mapping") ImportMapping mapping) {
        return importService.run(bytes(file), file.getOriginalFilename(), mapping, CurrentUser.id(jwt));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam Long trialId) {
        String name = trialService.get(trialId).getName();
        return ExcelExport.response(name + "-导入模板.xlsx", importService.template(trialId));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("文件是空的");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("文件读取失败，请重新上传");
        }
    }
}
