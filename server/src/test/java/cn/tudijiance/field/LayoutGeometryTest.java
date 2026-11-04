package cn.tudijiance.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.FieldLayout.Block;
import cn.tudijiance.field.FieldLayout.Fit;
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.FieldLayout.InnerSize;
import cn.tudijiance.field.FieldLayout.Span;
import cn.tudijiance.field.FieldLayout.Unit;
import cn.tudijiance.field.LayoutGeometry.Flat;
import cn.tudijiance.field.LayoutGeometry.Leaf;

class LayoutGeometryTest {

    private static final Unit METER = new Unit("米", "m", 1.0);

    private static Inner inner(int rows, int cols) {
        return new Inner(nCopies(rows, 1.0), nCopies(cols, 1.0), List.of(), Map.of());
    }

    private static List<Double> nCopies(int n, double v) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(v);
        }
        return out;
    }

    /** 两行区块：第一行 2 个种植区块（各 2 行 3 列小区），中间一条道路（合并成一整行），第三行 1 个区块 4 行 2 列 */
    private static FieldLayout sample() {
        List<Block> blocks = List.of(
                new Block("B1", 0, 0, "重复Ⅰ", FieldLayout.PLANT, null, inner(2, 3)),
                new Block("B2", 0, 1, "重复Ⅱ", FieldLayout.PLANT, null, inner(2, 3)),
                new Block("R1", 1, 0, "道路", FieldLayout.ROAD, null, null),
                new Block("B3", 2, 0, "重复Ⅲ", FieldLayout.PLANT, null, inner(4, 2)),
                new Block("B4", 2, 1, "空地", FieldLayout.BLANK, null, null));
        return new FieldLayout(1, METER, List.of(13.3, 1.0, 13.3), List.of(6.0, 6.0),
                List.of(new Span(1, 0, 1, 2)), blocks, 5, new InnerSize(2, 3), 0.0);
    }

    @Test
    void flattensPlantBlocksIntoAGrid() {
        FieldLayout l = sample();
        LayoutGeometry.validate(l);
        Flat flat = LayoutGeometry.flatten(l);
        assertThat(flat.leaves()).hasSize(6 + 6 + 8);
        // 第一行区块占 2 行，道路占 0 行，第三行区块占 4 行；列：第一列 max(3, 2) = 3，第二列 3
        assertThat(flat.rowCount()).isEqualTo(6);
        assertThat(flat.colCount()).isEqualTo(6);
        Leaf firstOfB2 = flat.leaves().stream().filter(x -> x.block().id().equals("B2")).findFirst().orElseThrow();
        assertThat(firstOfB2.rowNo()).isEqualTo(1);
        assertThat(firstOfB2.colNo()).isEqualTo(4);
        Leaf lastOfB3 = flat.leaves().getLast();
        assertThat(lastOfB3.rowNo()).isEqualTo(6);
        assertThat(lastOfB3.colNo()).isEqualTo(2);
        // 小区大小：6 m 宽分 3 列，13.3 m 高分 2 行
        assertThat(firstOfB2.widthM()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(firstOfB2.lengthM()).isEqualTo(6.65, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void mergedInnerCellsAndShrunkBlocks() {
        FieldLayout base = sample();
        List<Block> blocks = new ArrayList<>(base.blocks());
        // B1 缩成 4 m 宽（两边是道路），里面第一行的 3 格合并成一个小区
        blocks.set(0, new Block("B1", 0, 0, "重复Ⅰ", FieldLayout.PLANT, new Fit(1, 0, 4, 13.3),
                new Inner(List.of(1.0, 1.0), List.of(1.0, 1.0, 2.0), List.of(new Span(0, 0, 1, 3)), Map.of("0,0", 7L))));
        FieldLayout l = new FieldLayout(1, METER, base.rows(), base.cols(), base.merges(), blocks, 5, null, 0.0);
        LayoutGeometry.validate(l);
        List<Leaf> b1 = LayoutGeometry.flatten(l).leaves().stream().filter(x -> x.block().id().equals("B1")).toList();
        assertThat(b1).hasSize(4);
        assertThat(b1.getFirst().plotId()).isEqualTo(7L);
        assertThat(b1.getFirst().widthM()).isEqualTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        // 第二行：宽 1:1:2 分 4 m
        assertThat(b1.get(3).widthM()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void rejectsBrokenLayouts() {
        FieldLayout base = sample();
        // 区块超出槽位
        List<Block> blocks = new ArrayList<>(base.blocks());
        blocks.set(0, new Block("B1", 0, 0, "x", FieldLayout.PLANT, new Fit(3, 0, 4, 13.3), inner(2, 3)));
        assertThatThrownBy(() -> LayoutGeometry.validate(
                new FieldLayout(1, METER, base.rows(), base.cols(), base.merges(), blocks, 5, null, 0.0)))
                .isInstanceOf(ApiException.class).hasMessageContaining("超出");
        // 合并重叠
        assertThatThrownBy(() -> LayoutGeometry.validate(new FieldLayout(1, METER, base.rows(), base.cols(),
                List.of(new Span(1, 0, 1, 2), new Span(0, 1, 2, 1)), base.blocks(), 5, null, 0.0)))
                .isInstanceOf(ApiException.class);
        // 区块数和网格对不上
        assertThatThrownBy(() -> LayoutGeometry.validate(new FieldLayout(1, METER, base.rows(), base.cols(),
                List.of(), base.blocks(), 5, null, 0.0))).isInstanceOf(ApiException.class).hasMessageContaining("对不上");
        // 单位
        assertThatThrownBy(() -> LayoutGeometry.validate(new FieldLayout(1, new Unit("步", "步", 0.0), base.rows(),
                base.cols(), base.merges(), base.blocks(), 5, null, 0.0))).isInstanceOf(ApiException.class);
    }

    @Test
    void legacyFieldBecomesOneBlock() {
        Field f = new Field();
        f.setRowCount(2);
        f.setColCount(3);
        List<Plot> plots = new ArrayList<>();
        long id = 1;
        for (int r = 1; r <= 2; r++) {
            for (int c = 1; c <= 3; c++) {
                Plot p = new Plot();
                p.setId(id++);
                p.setRowNo(r);
                p.setColNo(c);
                p.setLengthM(new BigDecimal("6.65"));
                p.setWidthM(new BigDecimal("2.00"));
                plots.add(p);
            }
        }
        FieldLayout l = LayoutGeometry.legacy(f, plots);
        LayoutGeometry.validate(l);
        assertThat(l.rows()).containsExactly(13.3);
        assertThat(l.cols()).containsExactly(6.0);
        Flat flat = LayoutGeometry.flatten(l);
        assertThat(flat.leaves()).extracting(Leaf::plotId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(flat.leaves().get(4).rowNo()).isEqualTo(2);
        assertThat(flat.leaves().get(4).colNo()).isEqualTo(2);
    }
}
