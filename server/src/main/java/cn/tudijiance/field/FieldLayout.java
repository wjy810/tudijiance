package cn.tudijiance.field;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 地块布局：地块 → 区块 → 小区块，存在 field.layout（JSON）里。
 *
 * <ul>
 *   <li>地块是一个矩形（w × h，单位见 unit，可以自定义，例如米、尺、步）。</li>
 *   <li>blocks：摆在地块里的矩形，x、y 是左上角的位置，w、h 是大小，位置和大小随便改。
 *       kind 为 PLANT 种植区块时，里面再分 inner 网格，每格（合并的算一格）是一个小区块，
 *       inner.plots 记着这一格对应哪个小区块（键 "行,列"，从 0 开始）；ROAD 道路、GUARD 保护区、BLANK 空地没有小区块。
 *       没被盖住的地方就是空着的地。</li>
 *   <li>区块之间不能叠在一起；道路和道路可以交叉。</li>
 *   <li>inner.rows、inner.cols 是相对大小（比例），小区块实际的长宽按区块实际大小分。</li>
 *   <li>northDeg：真北方向，平面图"上方"顺时针转多少度是北。</li>
 * </ul>
 *
 * 字段定义和客户端 client/src/shared/fieldLayout.ts 一致。
 * 以前（version 1）是"几行几列区块 + 合并"的表格，迁移 V15 已经都换成了现在的样子。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldLayout(
        Integer version,
        Unit unit,
        Double w,
        Double h,
        List<Block> blocks,
        Integer nextBlockNo,
        InnerSize defaultInner,
        Double northDeg) {

    public static final int VERSION = 2;

    public static final String PLANT = "PLANT";
    public static final String ROAD = "ROAD";
    public static final String GUARD = "GUARD";
    public static final String BLANK = "BLANK";

    /** 长度单位：1 个单位等于多少米 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unit(String name, String symbol, Double meters) {
    }

    /** 合并区域：左上角 (r, c)，占 rs 行、cs 列 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Span(int r, int c, int rs, int cs) {
    }

    /** 一个区块（道路、保护区、空地也是）：在地块里的位置和大小，单位同 unit */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String id, String name, String kind, Double x, Double y, Double w, Double h, Inner inner) {

        public Block withInner(Inner in) {
            return new Block(id, name, kind, x, y, w, h, in);
        }
    }

    /**
     * 区块里的小区块网格；plots：格子（"行,列"）→ 小区块 id，新加的格子没有；
     * kinds：格子（"行,列"）→ 类型（ROAD 道路、GUARD 保护区、BLANK 空地），没写的是种植小区块
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inner(List<Double> rows, List<Double> cols, List<Span> merges, Map<String, Long> plots,
                        Map<String, String> kinds) {

        public String kindAt(String key) {
            String k = kinds == null ? null : kinds.get(key);
            return k == null ? PLANT : k;
        }
    }

    /** 新加的区块默认分几行几列小区块 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InnerSize(int rows, int cols) {
    }

    public FieldLayout withBlocks(List<Block> next) {
        return new FieldLayout(version, unit, w, h, next, nextBlockNo, defaultInner, northDeg);
    }
}
