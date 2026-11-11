package cn.tudijiance.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.field.FieldLayout.Block;
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.FieldLayout.InnerSize;
import cn.tudijiance.field.FieldLayout.Span;
import cn.tudijiance.field.FieldLayout.Unit;

/**
 * 地块布局的检查和计算：每个小区块的实际大小、在"拉平的网格"里的行号列号。
 *
 * 拉平的网格是给手机端、大屏、导入这些还按"第几行第几列"处理小区块的地方用的。区块是随便摆的，
 * 所以先按种植区块左上角的位置排出"第几排、第几列区块"：上边一样高的算一排，左边对齐的算一列；
 * 每一排区块占的行数 = 这一排里各区块小区块行数的最大值，列同理（一个区块跨了几排时，行数不够就补在它跨到的最后一排）；
 * 小区块的行号 = 它所在区块之前各排占的行数 + 它在区块里的行号。区块不叠在一起时，行号列号不会重复。
 */
public final class LayoutGeometry {

    public static final int MAX_BLOCKS = 400;
    public static final int MAX_INNER_TRACKS = 60;
    public static final int MAX_PLOTS = 3000;
    public static final double MAX_FIELD = 100000;
    public static final double MIN_SIZE = 0.01;
    /** 两个矩形挨着还是叠着：差这么一点不算叠（位置只记到小数点后 4 位） */
    public static final double TOL = 1e-3;
    private static final Set<String> KINDS = Set.of(FieldLayout.PLANT, FieldLayout.ROAD, FieldLayout.GUARD, FieldLayout.BLANK);

    private LayoutGeometry() {
    }

    /** 一个小区块：所在区块、在区块里的位置（合并时是左上角和跨度）、实际大小（米）、拉平后的行号列号（从 1 开始） */
    public record Leaf(Block block, Span cell, Long plotId, double widthM, double lengthM, int rowNo, int colNo) {
        public String key() {
            return cell.r() + "," + cell.c();
        }

        /** 这一格的小区块类型（PLOT、PATH、GUARD、BLANK） */
        public String plotKind() {
            return Plot.kindOfCell(block.inner().kindAt(key()));
        }
    }

    public record Flat(List<Leaf> leaves, int rowCount, int colCount) {
    }

    // ---------- 检查 ----------

    public static void validate(FieldLayout l) {
        if (l == null) {
            throw ApiException.badRequest("没有布局");
        }
        if (l.version() == null || l.version() != FieldLayout.VERSION) {
            throw ApiException.badRequest("布局的格式是旧的，请把电脑端、手机端升级到最新版再改地块");
        }
        Unit u = l.unit();
        if (u == null || u.meters() == null || !(u.meters() > 0) || u.meters() > 1000) {
            throw ApiException.badRequest("长度单位不对：1 个单位要在 0 到 1000 米之间");
        }
        if (blank(u.name()) || u.name().length() > 10 || (u.symbol() != null && u.symbol().length() > 10)) {
            throw ApiException.badRequest("长度单位的名称要填，不超过 10 个字");
        }
        if (l.w() == null || l.h() == null || !(l.w() > 0) || !(l.h() > 0) || l.w() > MAX_FIELD || l.h() > MAX_FIELD) {
            throw ApiException.badRequest("地块大小不对");
        }
        if (l.blocks() == null || l.blocks().size() > MAX_BLOCKS) {
            throw ApiException.badRequest("区块、道路加起来最多 " + MAX_BLOCKS + " 个");
        }
        Set<String> ids = new HashSet<>();
        int plotCount = 0;
        for (Block b : l.blocks()) {
            if (blank(b.id()) || b.id().length() > 32 || !ids.add(b.id())) {
                throw ApiException.badRequest("区块编号重复或为空");
            }
            if (b.name() != null && b.name().length() > 30) {
                throw ApiException.badRequest("区块名称不能超过 30 个字");
            }
            if (!KINDS.contains(b.kind())) {
                throw ApiException.badRequest("区块类型不对：" + b.kind());
            }
            if (b.x() == null || b.y() == null || b.w() == null || b.h() == null
                    || !(b.w() >= MIN_SIZE - 1e-9) || !(b.h() >= MIN_SIZE - 1e-9)) {
                throw ApiException.badRequest("区块" + label(b) + "的位置或大小不对");
            }
            if (b.x() < -TOL || b.y() < -TOL || b.x() + b.w() > l.w() + TOL || b.y() + b.h() > l.h() + TOL) {
                throw ApiException.badRequest("区块" + label(b) + "超出了地块");
            }
            if (FieldLayout.PLANT.equals(b.kind())) {
                Inner in = b.inner();
                if (in == null) {
                    throw ApiException.badRequest("区块" + label(b) + "没有小区块网格");
                }
                tracks(in.rows(), MAX_INNER_TRACKS, "区块" + label(b) + "里小区块的行");
                tracks(in.cols(), MAX_INNER_TRACKS, "区块" + label(b) + "里小区块的列");
                List<Span> cells = anchors(in.rows().size(), in.cols().size(),
                        in.merges() == null ? List.of() : in.merges(), "区块" + label(b) + "里的小区块");
                plotCount += cells.size();
                if (in.plots() != null) {
                    Set<String> cellKeys = new HashSet<>();
                    cells.forEach(cs -> cellKeys.add(cs.r() + "," + cs.c()));
                    for (String k : in.plots().keySet()) {
                        if (!cellKeys.contains(k)) {
                            throw ApiException.badRequest("区块" + label(b) + "里的小区块位置不对：" + k);
                        }
                    }
                }
                if (in.kinds() != null) {
                    Set<String> cellKeys = new HashSet<>();
                    cells.forEach(cs -> cellKeys.add(cs.r() + "," + cs.c()));
                    for (Map.Entry<String, String> e : in.kinds().entrySet()) {
                        if (!cellKeys.contains(e.getKey()) || !KINDS.contains(e.getValue())) {
                            throw ApiException.badRequest("区块" + label(b) + "里小区块的类型不对：" + e.getKey());
                        }
                    }
                }
            }
        }
        if (plotCount > MAX_PLOTS) {
            throw ApiException.badRequest("小区块太多了：一块实验田最多 " + MAX_PLOTS + " 个小区块");
        }
        // 区块之间不能叠在一起；道路和道路可以交叉
        List<Block> blocks = l.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                Block a = blocks.get(i);
                Block b = blocks.get(j);
                if (FieldLayout.ROAD.equals(a.kind()) && FieldLayout.ROAD.equals(b.kind())) {
                    continue;
                }
                if (overlaps(a, b)) {
                    throw ApiException.badRequest(label(a) + "和" + label(b) + "叠在一起了，挪开或者改小一个再保存");
                }
            }
        }
        InnerSize d = l.defaultInner();
        if (d != null && (d.rows() < 1 || d.cols() < 1 || d.rows() > MAX_INNER_TRACKS || d.cols() > MAX_INNER_TRACKS)) {
            throw ApiException.badRequest("默认的小区块行列数不对");
        }
    }

    static boolean overlaps(Block a, Block b) {
        return Math.min(a.x() + a.w(), b.x() + b.w()) - Math.max(a.x(), b.x()) > TOL
                && Math.min(a.y() + a.h(), b.y() + b.h()) - Math.max(a.y(), b.y()) > TOL;
    }

    private static String label(Block b) {
        return "“" + (blank(b.name()) ? b.id() : b.name()) + "”";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void tracks(List<Double> sizes, int max, String what) {
        if (sizes == null || sizes.isEmpty() || sizes.size() > max) {
            throw ApiException.badRequest(what + "数要在 1 到 " + max + " 之间");
        }
        for (Double s : sizes) {
            if (s == null || !(s > 0) || s > 100000) {
                throw ApiException.badRequest(what + "的大小要大于 0");
            }
        }
    }

    /** 网格的所有格子（合并的按锚点算一个），按行、列排序；顺便检查合并区域 */
    static List<Span> anchors(int rows, int cols, List<Span> merges, String what) {
        boolean[][] covered = new boolean[rows][cols];
        List<Span> out = new ArrayList<>();
        for (Span m : merges) {
            if (m.rs() < 1 || m.cs() < 1 || m.rs() * m.cs() < 2 || m.r() < 0 || m.c() < 0
                    || m.r() + m.rs() > rows || m.c() + m.cs() > cols) {
                throw ApiException.badRequest(what + "的合并范围不对");
            }
            for (int r = m.r(); r < m.r() + m.rs(); r++) {
                for (int c = m.c(); c < m.c() + m.cs(); c++) {
                    if (covered[r][c]) {
                        throw ApiException.badRequest(what + "的合并范围重叠了");
                    }
                    covered[r][c] = true;
                }
            }
            out.add(m);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!covered[r][c]) {
                    out.add(new Span(r, c, 1, 1));
                }
            }
        }
        out.sort(Comparator.comparingInt(Span::r).thenComparingInt(Span::c));
        return out;
    }

    private static double sum(List<Double> a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += a.get(i);
        }
        return s;
    }

    // ---------- 计算小区块 ----------

    /** 差不多一样的位置算一个，从小到大 */
    private static List<Double> cluster(List<Double> values) {
        List<Double> out = new ArrayList<>();
        values.stream().sorted().forEach(v -> {
            if (out.isEmpty() || v - out.get(out.size() - 1) > TOL) {
                out.add(v);
            }
        });
        return out;
    }

    /** 位置 v 落在第几条上（starts 是 cluster 出来的） */
    private static int trackOf(List<Double> starts, double v) {
        int at = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= v + TOL) {
                at = i;
            }
        }
        return at;
    }

    /** 从 from 到 to 这一段盖住了哪几条（至少盖住 first 这一条）：返回最后一条的序号 */
    private static int lastCovered(List<Double> starts, int first, double end) {
        int last = first;
        for (int i = first + 1; i < starts.size(); i++) {
            // 留出 3 倍的余量：挨着的下一个区块最多和这个区块叠 TOL，它那一条的起点又可能比它自己靠前 TOL
            if (starts.get(i) < end - 3 * TOL) {
                last = i;
            }
        }
        return last;
    }

    /** 所有小区块（种植区块里的格子），按区块（从上到下、从左到右）、再按区块里的行列排序 */
    public static Flat flatten(FieldLayout l) {
        List<Block> plant = l.blocks().stream().filter(b -> FieldLayout.PLANT.equals(b.kind())).toList();
        List<Double> ys = cluster(plant.stream().map(Block::y).toList());
        List<Double> xs = cluster(plant.stream().map(Block::x).toList());
        int nr = ys.size();
        int nc = xs.size();
        int[] rowTracks = new int[nr];
        int[] colTracks = new int[nc];
        record Placed(Block block, int r, int r2, int c, int c2) {
        }
        List<Placed> placed = new ArrayList<>();
        for (Block b : plant) {
            int r = trackOf(ys, b.y());
            int c = trackOf(xs, b.x());
            placed.add(new Placed(b, r, lastCovered(ys, r, b.y() + b.h()), c, lastCovered(xs, c, b.x() + b.w())));
        }
        placed.sort(Comparator.comparingInt(Placed::r).thenComparingInt(Placed::c));
        // 先按只占一排（列）的区块算，再给跨几排（列）的区块补够
        for (Placed p : placed) {
            if (p.r2() == p.r()) {
                rowTracks[p.r()] = Math.max(rowTracks[p.r()], p.block().inner().rows().size());
            }
            if (p.c2() == p.c()) {
                colTracks[p.c()] = Math.max(colTracks[p.c()], p.block().inner().cols().size());
            }
        }
        for (Placed p : placed) {
            int haveR = 0;
            for (int r = p.r(); r <= p.r2(); r++) {
                haveR += rowTracks[r];
            }
            int needR = p.block().inner().rows().size();
            if (haveR < needR) {
                rowTracks[p.r2()] += needR - haveR;
            }
            int haveC = 0;
            for (int c = p.c(); c <= p.c2(); c++) {
                haveC += colTracks[c];
            }
            int needC = p.block().inner().cols().size();
            if (haveC < needC) {
                colTracks[p.c2()] += needC - haveC;
            }
        }
        int[] rowStart = new int[nr + 1];
        for (int r = 0; r < nr; r++) {
            rowStart[r + 1] = rowStart[r] + rowTracks[r];
        }
        int[] colStart = new int[nc + 1];
        for (int c = 0; c < nc; c++) {
            colStart[c + 1] = colStart[c] + colTracks[c];
        }

        double meters = l.unit().meters();
        List<Leaf> leaves = new ArrayList<>();
        for (Placed p : placed) {
            Block b = p.block();
            Inner in = b.inner();
            double rowSum = sum(in.rows(), 0, in.rows().size());
            double colSum = sum(in.cols(), 0, in.cols().size());
            Map<String, Long> ids = in.plots() == null ? Map.of() : in.plots();
            for (Span cell : anchors(in.rows().size(), in.cols().size(), in.merges() == null ? List.of() : in.merges(), "")) {
                double w = sum(in.cols(), cell.c(), cell.c() + cell.cs()) / colSum * b.w() * meters;
                double h = sum(in.rows(), cell.r(), cell.r() + cell.rs()) / rowSum * b.h() * meters;
                leaves.add(new Leaf(b, cell, ids.get(cell.r() + "," + cell.c()), w, h,
                        rowStart[p.r()] + cell.r() + 1, colStart[p.c()] + cell.c() + 1));
            }
        }
        return new Flat(leaves, Math.max(1, rowStart[nr]), Math.max(1, colStart[nc]));
    }

    // ---------- 以前按行列建的实验田 ----------

    /**
     * 还没有布局的实验田：整块地当作一个区块，里面是原来的 rowCount × colCount 个小区块；
     * 每行的高按这一行小区块最常见的长、每列的宽按这一列小区块最常见的宽（没填时按 1 米）。
     */
    public static FieldLayout legacy(Field field, List<Plot> plots) {
        int rows = Math.max(1, field.getRowCount());
        int cols = Math.max(1, field.getColCount());
        List<Double> innerRows = new ArrayList<>();
        List<Double> innerCols = new ArrayList<>();
        for (int r = 1; r <= rows; r++) {
            final int row = r;
            innerRows.add(mode(plots.stream().filter(p -> p.getRowNo() == row && p.getLengthM() != null)
                    .map(p -> p.getLengthM().doubleValue()).toList()));
        }
        for (int c = 1; c <= cols; c++) {
            final int col = c;
            innerCols.add(mode(plots.stream().filter(p -> p.getColNo() == col && p.getWidthM() != null)
                    .map(p -> p.getWidthM().doubleValue()).toList()));
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Plot p : plots) {
            if (p.getRowNo() >= 1 && p.getRowNo() <= rows && p.getColNo() >= 1 && p.getColNo() <= cols) {
                ids.put((p.getRowNo() - 1) + "," + (p.getColNo() - 1), p.getId());
            }
        }
        double h = round(innerRows.stream().mapToDouble(Double::doubleValue).sum());
        double w = round(innerCols.stream().mapToDouble(Double::doubleValue).sum());
        Block b = new Block("B1", "区块1", FieldLayout.PLANT, 0.0, 0.0, w, h,
                new Inner(innerRows, innerCols, List.of(), ids, null));
        return new FieldLayout(FieldLayout.VERSION, new Unit("米", "m", 1.0), w, h, List.of(b), 2,
                new InnerSize(rows, cols), 0.0);
    }

    private static double mode(List<Double> values) {
        Map<Double, Integer> counts = new HashMap<>();
        values.forEach(v -> counts.merge(v, 1, Integer::sum));
        return counts.entrySet().stream().filter(e -> e.getKey() > 0)
                .max(Map.Entry.<Double, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(1.0);
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
