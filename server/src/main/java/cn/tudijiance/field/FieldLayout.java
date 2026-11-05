package cn.tudijiance.field;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 实验田的区块布局：实验田 → 区块 → 小区两层网格，存在 field.layout（JSON）里。
 *
 * <ul>
 *   <li>区块网格：rows 是每一行区块的高、cols 是每一列区块的宽，单位见 unit（可以自定义，例如米、尺、步）；
 *       merges 是合并的区块（锚点在左上角）。</li>
 *   <li>blocks：每个区块（合并的按锚点算一个）。kind 为 PLANT 种植区块时，里面再分 inner 网格，每格（合并的算一格）是一个小区，
 *       inner.plots 记着这一格对应哪个小区（键 "行,列"，从 0 开始）；ROAD 道路、GUARD 保护区、BLANK 空地 没有小区。</li>
 *   <li>fit：特殊区块的实际大小和在原来位置（槽位）里的偏移；比槽位小时，槽位里其余的部分是道路，区块可以在槽位里移动。
 *       为空表示填满槽位。</li>
 *   <li>inner.rows、inner.cols 是相对大小（比例），小区实际的长宽按区块实际大小分。</li>
 *   <li>northDeg：真北方向，平面图"上方"顺时针转多少度是北。</li>
 * </ul>
 *
 * 字段定义和客户端 client/src/shared/fieldLayout.ts 一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldLayout(
        Integer version,
        Unit unit,
        List<Double> rows,
        List<Double> cols,
        List<Span> merges,
        List<Block> blocks,
        Integer nextBlockNo,
        InnerSize defaultInner,
        Double northDeg) {

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

    /** 特殊区块的实际大小和在槽位里的位置（单位同 unit） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fit(double x, double y, double w, double h) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String id, int r, int c, String name, String kind, Fit fit, Inner inner) {
    }

    /**
     * 区块里的小区网格；plots：格子（"行,列"）→ 小区 id，新加的格子没有；
     * kinds：格子（"行,列"）→ 类型（ROAD 道路、GUARD 保护区、BLANK 空地），没写的是种植小区
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inner(List<Double> rows, List<Double> cols, List<Span> merges, Map<String, Long> plots,
                        Map<String, String> kinds) {

        public String kindAt(String key) {
            String k = kinds == null ? null : kinds.get(key);
            return k == null ? PLANT : k;
        }
    }

    /** 新加的区块默认分几行几列小区 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InnerSize(int rows, int cols) {
    }
}
