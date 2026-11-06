package cn.tudijiance.breeding;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.breeding.BreedingDtos.Candidates;
import cn.tudijiance.breeding.BreedingDtos.GenerationRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantRequest;
import cn.tudijiance.breeding.BreedingDtos.PlantResult;
import cn.tudijiance.security.Roles;
import jakarta.validation.Valid;

/** 育种地块上的种植 */
@RestController
@RequestMapping("/api/breeding/fields/{fieldId}")
public class BreedingFieldController {

    private final LineService lineService;

    public BreedingFieldController(LineService lineService) {
        this.lineService = lineService;
    }

    /** 种植下拉框：接着的季种过的、它们的子代、这一季已经种了的、最近种过的 */
    @GetMapping("/candidates")
    @PreAuthorize(Roles.CAN_RECORD)
    public Candidates candidates(@PathVariable Long fieldId) {
        return lineService.candidates(fieldId);
    }

    @PutMapping("/plantings")
    @PreAuthorize(Roles.IS_TEACHER)
    public PlantResult plant(@PathVariable Long fieldId, @Valid @RequestBody PlantRequest req) {
        return lineService.plant(fieldId, req);
    }

    @PutMapping("/generation")
    @PreAuthorize(Roles.IS_TEACHER)
    public PlantResult setGeneration(@PathVariable Long fieldId, @Valid @RequestBody GenerationRequest req) {
        return lineService.setGeneration(fieldId, req);
    }
}
