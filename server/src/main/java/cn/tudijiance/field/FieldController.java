package cn.tudijiance.field;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.field.FieldDtos.FieldDetail;
import cn.tudijiance.field.FieldDtos.FieldRequest;
import cn.tudijiance.field.FieldDtos.PlotCodesRequest;
import cn.tudijiance.field.FieldDtos.PlotPatchRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/fields")
public class FieldController {

    private final FieldService fieldService;

    public FieldController(FieldService fieldService) {
        this.fieldService = fieldService;
    }

    @GetMapping
    public List<Field> list() {
        return fieldService.list();
    }

    @GetMapping("/{id}")
    public FieldDetail detail(@PathVariable Long id) {
        return fieldService.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public FieldDetail create(@Valid @RequestBody FieldRequest req) {
        return fieldService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public FieldDetail update(@PathVariable Long id, @Valid @RequestBody FieldRequest req) {
        return fieldService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        fieldService.delete(id);
    }

    @PutMapping("/{id}/plot-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Plot> updateCodes(@PathVariable Long id, @Valid @RequestBody PlotCodesRequest req) {
        return fieldService.updateCodes(id, req.codes());
    }

    @PatchMapping("/{id}/plots")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Plot> patchPlots(@PathVariable Long id, @Valid @RequestBody PlotPatchRequest req) {
        return fieldService.patchPlots(id, req);
    }
}
