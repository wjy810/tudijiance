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
import cn.tudijiance.field.FieldLayout.Fit;
import cn.tudijiance.field.FieldLayout.Inner;
import cn.tudijiance.field.FieldLayout.InnerSize;
import cn.tudijiance.field.FieldLayout.Span;
import cn.tudijiance.field.FieldLayout.Unit;

/**
 * 区块布局的检查和计算：每个小区的实际大小、在"拉平的网格"里的行号列号。
 *
 * 拉平的网格是给手机端、大屏、导入这些还按"第几行第几列"处理小区的地方用的：
 * 每一行区块占的行数 = 这一行里各区块小区行数的最大值（道路、空地占 0 行），列同理；
 * 小区的行号 = 它所在区块之前各行区块占的行数 + 它在区块里的行号。
 */
public final class LayoutGeometry {

    public static final int MAX_BLOCK_TRACKS = 50;
    public static final int MAX_INNER_TRACKS = 60;
    public static final int MAX_PLOTS = 3000;
    private static final double EPS = 1e-6;
    private static final Set<String> KINDS = Set.of(FieldLayout.PLANT, FieldLayout.ROAD, FieldLayout.GUARD, FieldLayout.BLANK);

    private LayoutGeometry() {
    }

    /** 一个小区：所在区块、在区块里的位置（合并时是左上角和跨度）、实际大小（米）、拉平后的行号列号（从 1 开始） */
    public record Leaf(Block block, Span cell, Long plotId, double widthM, double lengthM, int rowNo, int colNo) {
        public String key() {
            return cell.r() + "," + cell.c();
        }

        /** 这一格的小区类型（PLOT、PATH、GUARD、BLANK） */
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
        Unit u = l.unit();
        if (u == null || u.meters() == null || !(u.meters() > 0) || u.meters() > 1000) {
            throw ApiException.badRequest("长度单位不对：1 个单位要在 0 到 1000 米之间");
        }
        if (blank(u.name()) || u.name().length() > 10 || (u.symbol() != null && u.symbol().length() > 10)) {
            throw ApiException.badRequest("长度单位的名称要填，不超过 10 个字");
        }
        tracks(l.rows(), MAX_BLOCK_TRACKS, "区块的行");
        tracks(l.cols(), MAX_BLOCK_TRACKS, "区块的列");
        List<Span> merges = l.merges() == null ? List.of() : l.merges();
        List<Span> anchors = anchors(l.rows().size(), l.cols().size(), merges, "区块");
        if (l.blocks() == null || l.blocks().size() != anchors.size()) {
            throw ApiException.badRequest("区块和网格对不上");
        }
        Set<String> anchorKeys = new HashSet<>();
        anchors.forEach(a -> anchorKeys.add(a.r() + "," + a.c()));
        Set<String> ids = new HashSet<>();
        Set<String> seen = new HashSet<>();
        int plotCount = 0;
        for (Block b : l.blocks()) {
            if (blank(b.id()) || b.id().length() > 32 || !ids.add(b.id())) {
                throw ApiException.badRequest("区块编号重复或为空");
            }
            if (!anchorKeys.contains(b.r() + "," + b.c()) || !seen.add(b.r() + "," + b.c())) {
                throw ApiException.badRequest("区块位置不对：" + b.id());
            }
            if (b.name() != null && b.name().length() > 30) {
                throw ApiException.badRequest("区块名称不能超过 30 个字");
            }
            if (!KINDS.contains(b.kind())) {
                throw ApiException.badRequest("区块类型不对：" + b.kind());
            }
            Fit slot = slot(l, spanOf(merges, b.r(), b.c()));
            Fit fit = b.fit();
            if (fit != null) {
                if (!(fit.w() > 0) || !(fit.h() > 0) || fit.x() < -EPS || fit.y() < -EPS
                        || fit.x() + fit.w() > slot.w() * (1 + EPS) + EPS || fit.y() + fit.h() > slot.h() * (1 + EPS) + EPS) {
                    throw ApiException.badRequest("区块" + label(b) + "的大小或位置超出了原来的范围");
                }
            }
            if (FieldLayout.PLANT.equals(b.kind())) {
                Inner in = b.inner();
                if (in == null) {
                    throw ApiException.badRequest("区块" + label(b) + "没有小区网格");
                }
                tracks(in.rows(), MAX_INNER_TRACKS, "区块" + label(b) + "里小区的行");
                tracks(in.cols(), MAX_INNER_TRACKS, "区块" + label(b) + "里小区的列");
                List<Span> cells = anchors(in.rows().size(), in.cols().size(),
                        in.merges() == null ? List.of() : in.merges(), "区块" + label(b) + "里的小区");
                plotCount += cells.size();
                if (in.plots() != null) {
                    Set<String> cellKeys = new HashSet<>();
                    cells.forEach(cs -> cellKeys.add(cs.r() + "," + cs.c()));
                    for (String k : in.plots().keySet()) {
                        if (!cellKeys.contains(k)) {
                            throw ApiException.badRequest("区块" + label(b) + "里的小区位置不对：" + k);
                        }
                    }
                }
                if (in.kinds() != null) {
                    Set<String> cellKeys = new HashSet<>();
                    cells.forEach(cs -> cellKeys.add(cs.r() + "," + cs.c()));
                    for (Map.Entry<String, String> e : in.kinds().entrySet()) {
                        if (!cellKeys.contains(e.getKey()) || !KINDS.contains(e.getValue())) {
                            throw ApiException.badRequest("区块" + label(b) + "里小区的类型不对：" + e.getKey());
                        }
                    }
                }
            }
        }
        if (plotCount > MAX_PLOTS) {
            throw ApiException.badRequest("小区太多了：一块实验田最多 " + MAX_PLOTS + " 个小区");
        }
        InnerSize d = l.defaultInner();
        if (d != null && (d.rows() < 1 || d.cols() < 1 || d.rows() > MAX_INNER_TRACKS || d.cols() > MAX_INNER_TRACKS)) {
            throw ApiException.badRequest("默认的小区行列数不对");
        }
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

    static Span spanOf(List<Span> merges, int r, int c) {
        for (Span m : merges) {
            if (m.r() == r && m.c() == c) {
                return m;
            }
        }
        return new Span(r, c, 1, 1);
    }

    private static double sum(List<Double> a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += a.get(i);
        }
        return s;
    }

    /** 区块的槽位（单位同 unit） */
    static Fit slot(FieldLayout l, Span s) {
        return new Fit(sum(l.cols(), 0, s.c()), sum(l.rows(), 0, s.r()),
                sum(l.cols(), s.c(), s.c() + s.cs()), sum(l.rows(), s.r(), s.r() + s.rs()));
    }

    // ---------- 计算小区 ----------

    /** 所有小区（种植区块里的格子），按区块、再按区块里的行列排序 */
    public static Flat flatten(FieldLayout l) {
        List<Span> merges = l.merges() == null ? List.of() : l.merges();
        int nr = l.rows().size();
        int nc = l.cols().size();
        int[] rowTracks = new int[nr];
        int[] colTracks = new int[nc];
        List<Block> plant = l.blocks().stream().filter(b -> FieldLayout.PLANT.equals(b.kind()))
                .sorted(Comparator.comparingInt(Block::r).thenComparingInt(Block::c)).toList();
        // 先按没合并的方向算，再给跨多行（列）的区块补够
        for (Block b : plant) {
            Span s = spanOf(merges, b.r(), b.c());
            if (s.rs() == 1) {
                rowTracks[b.r()] = Math.max(rowTracks[b.r()], b.inner().rows().size());
            }
            if (s.cs() == 1) {
                colTracks[b.c()] = Math.max(colTracks[b.c()], b.inner().cols().size());
            }
        }
        for (Block b : plant) {
            Span s = spanOf(merges, b.r(), b.c());
            int haveR = 0;
            for (int r = s.r(); r < s.r() + s.rs(); r++) {
                haveR += rowTracks[r];
            }
            if (haveR < b.inner().rows().size()) {
                rowTracks[s.r() + s.rs() - 1] += b.inner().rows().size() - haveR;
            }
            int haveC = 0;
            for (int c = s.c(); c < s.c() + s.cs(); c++) {
                haveC += colTracks[c];
            }
            if (haveC < b.inner().cols().size()) {
                colTracks[s.c() + s.cs() - 1] += b.inner().cols().size() - haveC;
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
        for (Block b : plant) {
            Fit slot = slot(l, spanOf(merges, b.r(), b.c()));
            double bw = b.fit() == null ? slot.w() : b.fit().w();
            double bh = b.fit() == null ? slot.h() : b.fit().h();
            Inner in = b.inner();
            double rowSum = sum(in.rows(), 0, in.rows().size());
            double colSum = sum(in.cols(), 0, in.cols().size());
            Map<String, Long> ids = in.plots() == null ? Map.of() : in.plots();
            for (Span cell : anchors(in.rows().size(), in.cols().size(), in.merges() == null ? List.of() : in.merges(), "")) {
                double w = sum(in.cols(), cell.c(), cell.c() + cell.cs()) / colSum * bw * meters;
                double h = sum(in.rows(), cell.r(), cell.r() + cell.rs()) / rowSum * bh * meters;
                leaves.add(new Leaf(b, cell, ids.get(cell.r() + "," + cell.c()), w, h,
                        rowStart[b.r()] + cell.r() + 1, colStart[b.c()] + cell.c() + 1));
            }
        }
        return new Flat(leaves, Math.max(1, rowStart[nr]), Math.max(1, colStart[nc]));
    }

    // ---------- 以前按行列建的实验田 ----------

    /**
     * 还没有布局的实验田：整块地当作一个区块，里面是原来的 rowCount × colCount 个小区；
     * 每行的高按这一行小区最常见的长、每列的宽按这一列小区最常见的宽（没填时按 1 米）。
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
        Block b = new Block("B1", 0, 0, "区块1", FieldLayout.PLANT, null, new Inner(innerRows, innerCols, List.of(), ids, null));
        return new FieldLayout(1, new Unit("米", "m", 1.0), List.of(h), List.of(w), List.of(), List.of(b), 2,
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
