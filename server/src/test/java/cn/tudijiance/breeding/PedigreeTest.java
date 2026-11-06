package cn.tudijiance.breeding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.tudijiance.variety.Variety;

class PedigreeTest {

    private final Map<Long, Variety> lines = new HashMap<>();
    private long nextId = 1;

    private Variety founder(String name) {
        Variety v = new Variety();
        v.setId(nextId++);
        v.setName(name);
        v.setLineKind(Variety.FOUNDER);
        v.setLineCode(name);
        lines.put(v.getId(), v);
        return v;
    }

    private Variety cross(Variety mother, Variety father, String code) {
        Variety v = new Variety();
        v.setId(nextId++);
        v.setName(code);
        v.setLineKind(Variety.CROSS);
        v.setLineCode(code);
        v.setMotherId(mother.getId());
        v.setFatherId(father.getId());
        lines.put(v.getId(), v);
        return v;
    }

    private Variety select(Variety parent, int n) {
        Variety v = new Variety();
        v.setId(nextId++);
        v.setLineKind(Variety.SELECTION);
        v.setLineCode(Pedigree.selectionCode(parent.getLineCode(), n));
        v.setName(v.getLineCode());
        v.setParentId(parent.getId());
        lines.put(v.getId(), v);
        return v;
    }

    @Test
    void codes() {
        assertThat(Pedigree.crossCode(2025, 1)).isEqualTo("25-001");
        assertThat(Pedigree.crossCode(2026, 1234)).isEqualTo("26-1234");
        assertThat(Pedigree.crossCode(2005, 12)).isEqualTo("05-012");
        assertThat(Pedigree.selectionCode("25-001", 3)).isEqualTo("25-001-3");
        assertThat(Pedigree.selectionCode("周麦16", 1)).isEqualTo("周麦16-1");
    }

    @Test
    void names() {
        assertThat(Pedigree.nameProblem("校麦1号")).isNull();
        assertThat(Pedigree.nameProblem("周麦16/郑麦366")).contains("不能有");
        assertThat(Pedigree.nameProblem("A*2")).contains("不能有");
        assertThat(Pedigree.nameProblem(" ")).isNotNull();
    }

    @Test
    void purdyNotation() {
        Variety a = founder("周麦16");
        Variety b = founder("郑麦366");
        Variety c = founder("百农207");
        Variety d = founder("济麦22");
        Variety ab = cross(a, b, "25-001");
        Variety ab3 = select(ab, 3);
        Variety ab3c = cross(ab3, c, "26-001");
        Variety cd = cross(c, d, "26-002");
        Variety four = cross(ab, cd, "27-001");
        Variety third = cross(ab3c, d, "27-002");
        Variety back = cross(ab, a, "26-003");
        Variety back3 = cross(a, back, "27-003");
        Variety selA = select(a, 1);

        Map<Long, String> p = Pedigree.all(lines);
        assertThat(p.get(a.getId())).isEqualTo("周麦16");
        assertThat(p.get(ab.getId())).isEqualTo("周麦16/郑麦366");
        // 选株后代的系谱式和父本一样
        assertThat(p.get(ab3.getId())).isEqualTo("周麦16/郑麦366");
        assertThat(p.get(selA.getId())).isEqualTo("周麦16");
        assertThat(p.get(ab3c.getId())).isEqualTo("周麦16/郑麦366//百农207");
        assertThat(p.get(four.getId())).isEqualTo("周麦16/郑麦366//百农207/济麦22");
        assertThat(p.get(third.getId())).isEqualTo("周麦16/郑麦366//百农207/3/济麦22");
        // 回交
        assertThat(p.get(back.getId())).isEqualTo("周麦16*2/郑麦366");
        assertThat(p.get(back3.getId())).isEqualTo("周麦16*3/郑麦366");
    }

    @Test
    void naturalOrderOfCodes() {
        assertThat(LineService.naturalCompare("25-002", "25-010")).isNegative();
        assertThat(LineService.naturalCompare("25-001-2", "25-001-10")).isNegative();
        assertThat(LineService.naturalCompare("25-001", "25-001-1")).isNegative();
        assertThat(LineService.naturalCompare("26-001", "25-999")).isPositive();
    }

    @Test
    void formerNames() {
        assertThat(LineService.appendFormer(null, "25-001-3", "25-001-3")).isNull();
        assertThat(LineService.appendFormer(null, "校杂1号", "25-001")).isEqualTo("校杂1号");
        assertThat(LineService.appendFormer("校杂1号", "校麦1号", "25-001")).isEqualTo("校杂1号、校麦1号");
        assertThat(LineService.appendFormer("校杂1号", "校杂1号", "25-001")).isEqualTo("校杂1号");
    }
}
