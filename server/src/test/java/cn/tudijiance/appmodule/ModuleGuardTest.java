package cn.tudijiance.appmodule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModuleGuardTest {

    @Test
    void mapsPathsToModules() {
        assertThat(ModuleGuard.moduleOf("GET", "/api/screen")).isEqualTo("screen");
        assertThat(ModuleGuard.moduleOf("GET", "/api/stats/record-book/export")).isEqualTo("record_book");
        assertThat(ModuleGuard.moduleOf("PUT", "/api/trials/12/book")).isEqualTo("record_book");
        assertThat(ModuleGuard.moduleOf("GET", "/api/stats/variety-summary")).isEqualTo("stats");
        assertThat(ModuleGuard.moduleOf("POST", "/api/analysis/query")).isEqualTo("analysis");
        assertThat(ModuleGuard.moduleOf("POST", "/api/imports/preview")).isEqualTo("import");
        assertThat(ModuleGuard.moduleOf("POST", "/api/ingest/devices/S1/readings")).isEqualTo("devices");
        assertThat(ModuleGuard.moduleOf("GET", "/api/observations")).isEqualTo("data");
        assertThat(ModuleGuard.moduleOf("GET", "/api/observations/export")).isEqualTo("data");
    }

    @Test
    void leavesCoreRecordingAlone() {
        // 录入、平面图、试验布局不能被停用模块挡住
        assertThat(ModuleGuard.moduleOf("POST", "/api/observations")).isNull();
        assertThat(ModuleGuard.moduleOf("GET", "/api/trials/3/status")).isNull();
        assertThat(ModuleGuard.moduleOf("GET", "/api/trials/3/layout")).isNull();
        assertThat(ModuleGuard.moduleOf("GET", "/api/fields/1")).isNull();
        assertThat(ModuleGuard.moduleOf("GET", "/api/modules")).isNull();
    }
}
