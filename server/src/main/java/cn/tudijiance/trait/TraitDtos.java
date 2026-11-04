package cn.tudijiance.trait;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TraitDtos {

    private TraitDtos() {
    }

    /** 录入项目，带分级选项和表头别名 */
    public record TraitView(TraitDef trait, List<TraitOption> options, List<String> aliases) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }

    public record ImportantRequest(@NotNull Boolean important) {
    }

    public record CustomTraitRequest(
            @NotBlank @Size(max = 50) String name,
            @NotBlank String dataType,
            @Size(max = 16) String unit,
            @Min(0) @Max(4) Integer precisionDigits,
            @Size(max = 50) List<String> options,
            @Size(max = 200) String description,
            /** 同时记下的表头别名，例如导入时按表头新建项目，就把这个表头记下来 */
            @Size(max = 20) List<String> aliases,
            /** 标为重要项目 */
            Boolean important) {
    }

    public record AliasRequest(@NotNull @Size(max = 50) List<String> aliases) {
    }
}
