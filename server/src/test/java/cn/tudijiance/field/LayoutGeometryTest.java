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
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.FieldLayout.InnerSize;
import cn.tudijiance.field.FieldLayout.Span;
import cn.tudijiance.field.FieldLayout.Unit;
import cn.tudijiance.field.LayoutGeometry.Flat;
import cn.tudijiance.field.LayoutGeometry.Leaf;

class LayoutGeometryTest {

    private static final Unit METER = new Unit("米", "m", 1.0);

    private static Inner inner(int rows, int cols) {
        return new Inner(nCopies(rows, 1.0), nCopies(cols, 1.0), List.of(), Map.of(), null);
    }

    private static List<Double> nCopies(int n, double v) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(v);
        }
        return out;
    }

    /**
     * 第一排 2 个种植区块（各 2 行 3 列小区块，6 m × 13.3 m），下面一条通到头的道路，
     * 再下面 1 个区块 4 行 2 列，旁边是空地
     */
    private static FieldLayout sample() {
        List<Block> blocks = List.of(
                new Block("B1", "重复Ⅰ", FieldLayout.PLANT, 0.0, 0.0, 6.0, 13.3, inner(2, 3)),
                new Block("B2", "重复Ⅱ", FieldLayout.PLANT, 6.0, 0.0, 6.0, 13.3, inner(2, 3)),
                new Block("R1", "道路", FieldLayout.ROAD, 0.0, 13.3, 12.0, 1.0, null),
                new Block("B3", "重复Ⅲ", FieldLayout.PLANT, 0.0, 14.3, 6.0, 13.3, inner(4, 2)),
                new Block("B4", "空地", FieldLayout.BLANK, 6.0, 14.3, 6.0, 13.3, null));
        return new FieldLayout(2, METER, 12.0, 27.6, blocks, 5, new InnerSize(2, 3), 0.0);
    }

    private static FieldLayout with(FieldLayout base, List<Block> blocks) {
        return new FieldLayout(2, base.unit(), base.w(), base.h(), blocks, 9, null, 0.0);
    }

    @Test
    void flattensPlantBlocksIntoAGrid() {
        FieldLayout l = sample();
        LayoutGeometry.validate(l);
        Flat flat = LayoutGeometry.flatten(l);
        assertThat(flat.leaves()).hasSize(6 + 6 + 8);
        // 第一排区块占 2 行，道路不占，第二排区块占 4 行；列：第一列 max(3, 2) = 3，第二列 3
        assertThat(flat.rowCount()).isEqualTo(6);
        assertThat(flat.colCount()).isEqualTo(6);
        Leaf firstOfB2 = flat.leaves().stream().filter(x -> x.block().id().equals("B2")).findFirst().orElseThrow();
        assertThat(firstOfB2.rowNo()).isEqualTo(1);
        assertThat(firstOfB2.colNo()).isEqualTo(4);
        Leaf lastOfB3 = flat.leaves().getLast();
        assertThat(lastOfB3.rowNo()).isEqualTo(6);
        assertThat(lastOfB3.colNo()).isEqualTo(2);
        // 小区块大小：6 m 宽分 3 列，13.3 m 高分 2 行
        assertThat(firstOfB2.widthM()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(firstOfB2.lengthM()).isEqualTo(6.65, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void mergedInnerCellsScaleWithTheBlock() {
        FieldLayout base = sample();
        List<Block> blocks = new ArrayList<>(base.blocks());
        // B1 只有 4 m 宽（两边空着），里面第一行的 3 格合并成一个小区块
        blocks.set(0, new Block("B1", "重复Ⅰ", FieldLayout.PLANT, 1.0, 0.0, 4.0, 13.3,
                new Inner(List.of(1.0, 1.0), List.of(1.0, 1.0, 2.0), List.of(new Span(0, 0, 1, 3)), Map.of("0,0", 7L), null)));
        FieldLayout l = with(base, blocks);
        LayoutGeometry.validate(l);
        List<Leaf> b1 = LayoutGeometry.flatten(l).leaves().stream().filter(x -> x.block().id().equals("B1")).toList();
        assertThat(b1).hasSize(4);
        assertThat(b1.getFirst().plotId()).isEqualTo(7L);
        assertThat(b1.getFirst().widthM()).isEqualTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        // 第二行：宽 1:1:2 分 4 m
        assertThat(b1.get(3).widthM()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void freelyPlacedBlocksNeverShareARowAndColumn() {
        // 随便摆：左边一个高的区块（6 行），右边错开摆两个矮的，下面再横着摆一个宽的
        List<Block> blocks = List.of(
                new Block("A", "高的", FieldLayout.PLANT, 0.0, 0.0, 4.0, 30.0, inner(6, 2)),
                new Block("B", "右上", FieldLayout.PLANT, 5.0, 2.0, 6.0, 10.0, inner(2, 3)),
                new Block("C", "右中", FieldLayout.PLANT, 5.5, 13.0, 6.0, 10.0, inner(3, 4)),
                new Block("D", "下面", FieldLayout.PLANT, 0.0, 31.0, 12.0, 5.0, inner(1, 5)),
                new Block("R", "道路", FieldLayout.ROAD, 4.0, 0.0, 1.0, 30.0, null));
        FieldLayout l = new FieldLayout(2, METER, 12.0, 36.0, blocks, 9, null, 0.0);
        LayoutGeometry.validate(l);
        Flat flat = LayoutGeometry.flatten(l);
        assertThat(flat.leaves()).hasSize(12 + 6 + 12 + 5);
        // 行号列号是小区块的位置（数据库里不能重复）
        assertThat(flat.leaves().stream().map(x -> x.rowNo() + "," + x.colNo()).distinct().count()).isEqualTo(35);
        assertThat(flat.leaves()).allSatisfy(x -> {
            assertThat(x.rowNo()).isBetween(1, flat.rowCount());
            assertThat(x.colNo()).isBetween(1, flat.colCount());
        });
        // 下面那个区块排在所有区块的行后面
        int lastRowAbove = flat.leaves().stream().filter(x -> !x.block().id().equals("D")).mapToInt(Leaf::rowNo).max().orElse(0);
        assertThat(flat.leaves().stream().filter(x -> x.block().id().equals("D")).mapToInt(Leaf::rowNo).min().orElse(0))
                .isGreaterThan(lastRowAbove);
    }

    @Test
    void rejectsBrokenLayouts() {
        FieldLayout base = sample();
        // 区块超出地块
        List<Block> outside = new ArrayList<>(base.blocks());
        outside.set(1, new Block("B2", "x", FieldLayout.PLANT, 8.0, 0.0, 6.0, 13.3, inner(2, 3)));
        assertThatThrownBy(() -> LayoutGeometry.validate(with(base, outside)))
                .isInstanceOf(ApiException.class).hasMessageContaining("超出");
        // 两个区块叠在一起
        List<Block> stacked = new ArrayList<>(base.blocks());
        stacked.set(1, new Block("B2", "重复Ⅱ", FieldLayout.PLANT, 5.0, 0.0, 6.0, 13.3, inner(2, 3)));
        assertThatThrownBy(() -> LayoutGeometry.validate(with(base, stacked)))
                .isInstanceOf(ApiException.class).hasMessageContaining("叠在一起");
        // 道路压在区块上也不行；道路和道路可以交叉
        List<Block> roadOver = new ArrayList<>(base.blocks());
        roadOver.add(new Block("R2", "道路", FieldLayout.ROAD, 2.0, 0.0, 1.0, 27.6, null));
        assertThatThrownBy(() -> LayoutGeometry.validate(with(base, roadOver)))
                .isInstanceOf(ApiException.class).hasMessageContaining("叠在一起");
        List<Block> crossing = new ArrayList<>(base.blocks().subList(0, 3));
        crossing.add(new Block("R2", "道路", FieldLayout.ROAD, 0.0, 13.0, 1.0, 5.0, null));
        crossing.set(0, new Block("B1", "重复Ⅰ", FieldLayout.PLANT, 1.0, 0.0, 5.0, 13.0, inner(2, 3)));
        LayoutGeometry.validate(with(base, crossing));
        // 太小、种植区块没有小区块网格
        List<Block> tiny = new ArrayList<>(base.blocks());
        tiny.set(4, new Block("B4", "空地", FieldLayout.BLANK, 6.0, 14.3, 0.0, 13.3, null));
        assertThatThrownBy(() -> LayoutGeometry.validate(with(base, tiny))).isInstanceOf(ApiException.class);
        List<Block> noInner = new ArrayList<>(base.blocks());
        noInner.set(0, new Block("B1", "重复Ⅰ", FieldLayout.PLANT, 0.0, 0.0, 6.0, 13.3, null));
        assertThatThrownBy(() -> LayoutGeometry.validate(with(base, noInner)))
                .isInstanceOf(ApiException.class).hasMessageContaining("小区块网格");
        // 单位、旧格式
        assertThatThrownBy(() -> LayoutGeometry.validate(new FieldLayout(2, new Unit("步", "步", 0.0), 12.0, 27.6,
                base.blocks(), 5, null, 0.0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LayoutGeometry.validate(new FieldLayout(1, METER, 12.0, 27.6, base.blocks(), 5, null, 0.0)))
                .isInstanceOf(ApiException.class).hasMessageContaining("旧的");
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
        assertThat(l.h()).isEqualTo(13.3);
        assertThat(l.w()).isEqualTo(6.0);
        assertThat(l.blocks().getFirst().w()).isEqualTo(6.0);
        Flat flat = LayoutGeometry.flatten(l);
        assertThat(flat.leaves()).extracting(Leaf::plotId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(flat.leaves().get(4).rowNo()).isEqualTo(2);
        assertThat(flat.leaves().get(4).colNo()).isEqualTo(2);
    }
}
