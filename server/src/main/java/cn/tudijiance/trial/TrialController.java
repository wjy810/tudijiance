package cn.tudijiance.trial;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.trial.TrialDtos.AssignRequest;
import cn.tudijiance.trial.TrialDtos.Layout;
import cn.tudijiance.trial.TrialDtos.LinkRequest;
import cn.tudijiance.trial.TrialDtos.TrialRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/trials")
public class TrialController {

    private final TrialService trialService;

    public TrialController(TrialService trialService) {
        this.trialService = trialService;
    }

    @GetMapping
    public List<Trial> list(@RequestParam(required = false) Long fieldId) {
        return trialService.list(fieldId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Trial create(@Valid @RequestBody TrialRequest req) {
        return trialService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Trial update(@PathVariable Long id, @Valid @RequestBody TrialRequest req) {
        return trialService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        trialService.delete(id);
    }

    @GetMapping("/{id}/layout")
    public Layout layout(@PathVariable Long id) {
        return trialService.layout(id);
    }

    @PutMapping("/{id}/plantings")
    @PreAuthorize("hasRole('ADMIN')")
    public Layout assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        return trialService.assign(id, req);
    }

    @PostMapping("/{id}/control-links")
    @PreAuthorize("hasRole('ADMIN')")
    public Layout createLink(@PathVariable Long id, @Valid @RequestBody LinkRequest req) {
        return trialService.createLink(id, req);
    }

    @DeleteMapping("/{id}/control-links/{linkId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Layout deleteLink(@PathVariable Long id, @PathVariable Long linkId) {
        return trialService.deleteLink(id, linkId);
    }
}
