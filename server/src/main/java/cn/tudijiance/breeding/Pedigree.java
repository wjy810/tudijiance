package cn.tudijiance.breeding;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import cn.tudijiance.variety.Variety;

/**
 * 编号和系谱式（育种上通用的 Purdy 写法）。
 *
 * <pre>
 * 杂交组合编号：杂交年份后两位-当年第几个，如 25-001；超过 999 自动变四位
 * 选株后代编号：父编号-第几株，如 25-001-3、25-001-3-2、周麦16-1
 * 系谱式：单交 母本/父本；第二次杂交用 //，第三次用 /3/，再往上 /4/……；
 *        回交在重复用的亲本后面写 *次数，如 周麦16*2/郑麦366；选株后代的系谱式和父本相同
 * </pre>
 */
public final class Pedigree {

    /** 名称里不能有的字符：系谱式要用 */
    private static final Pattern FORBIDDEN = Pattern.compile("[/*]");
    private static final int MAX_DEPTH = 60;

    private Pedigree() {
    }

    public static String crossCode(int year, int seq) {
        return String.format("%02d-%03d", Math.floorMod(year, 100), seq);
    }

    public static String selectionCode(String parentCode, int seq) {
        return parentCode + "-" + seq;
    }

    /** 名称里有"/"或"*"时返回提示，没问题返回 null */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "名称不能为空";
        }
        if (FORBIDDEN.matcher(name).find()) {
            return "名称里不能有“/”和“*”（系谱式要用）：" + name;
        }
        return null;
    }

    // ---------- 系谱式 ----------

    sealed interface Node permits Leaf, Cross, Back {
    }

    /** 一个亲本：原始材料，或者从原始材料选出来的（系谱式写原始材料的名字） */
    record Leaf(String name) implements Node {
    }

    record Cross(Node mother, Node father, int level) implements Node {
    }

    /** 回交：recurrent 用了 times 次，和 other 杂交 */
    record Back(String recurrent, int times, String other) implements Node {
    }

    static int level(Node n) {
        return switch (n) {
            case Leaf l -> 0;
            case Cross c -> c.level();
            case Back b -> 1;
        };
    }

    static Node cross(Node mother, Node father) {
        if (father instanceof Leaf a) {
            Node back = backcross(mother, a.name());
            if (back != null) {
                return back;
            }
        }
        if (mother instanceof Leaf a) {
            Node back = backcross(father, a.name());
            if (back != null) {
                return back;
            }
        }
        return new Cross(mother, father, Math.max(level(mother), level(father)) + 1);
    }

    /** side 是 A/B 或 A*n/B，又和 A 杂交：变成 A*(n+1)/B；否则返回 null */
    private static Node backcross(Node side, String name) {
        if (side instanceof Cross c && c.level() == 1 && c.mother() instanceof Leaf m && c.father() instanceof Leaf f) {
            if (m.name().equals(name)) {
                return new Back(name, 2, f.name());
            }
            if (f.name().equals(name)) {
                return new Back(name, 2, m.name());
            }
        }
        if (side instanceof Back b && b.recurrent().equals(name)) {
            return new Back(name, b.times() + 1, b.other());
        }
        return null;
    }

    static String render(Node n) {
        return switch (n) {
            case Leaf l -> l.name();
            case Cross c -> render(c.mother()) + separator(c.level()) + render(c.father());
            case Back b -> b.recurrent() + "*" + b.times() + "/" + b.other();
        };
    }

    static String separator(int level) {
        return switch (level) {
            case 1 -> "/";
            case 2 -> "//";
            default -> "/" + level + "/";
        };
    }

    /** 算所有品系的系谱式：品系 id → 系谱式 */
    public static Map<Long, String> all(Map<Long, Variety> lines) {
        Map<Long, Node> memo = new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        for (Variety v : lines.values()) {
            result.put(v.getId(), render(node(v, lines, memo, new java.util.HashSet<>(), 0)));
        }
        return result;
    }

    private static Node node(Variety v, Map<Long, Variety> lines, Map<Long, Node> memo, Set<Long> path, int depth) {
        Node cached = memo.get(v.getId());
        if (cached != null) {
            return cached;
        }
        Node n;
        if (depth > MAX_DEPTH || !path.add(v.getId())) {
            n = new Leaf(v.getName());
        } else if (Variety.CROSS.equals(v.getLineKind())) {
            Variety m = lines.get(v.getMotherId());
            Variety f = lines.get(v.getFatherId());
            n = m == null || f == null ? new Leaf(v.getName())
                    : cross(node(m, lines, memo, path, depth + 1), node(f, lines, memo, path, depth + 1));
        } else if (Variety.SELECTION.equals(v.getLineKind())) {
            Variety p = lines.get(v.getParentId());
            n = p == null ? new Leaf(v.getName()) : node(p, lines, memo, path, depth + 1);
        } else {
            n = new Leaf(v.getName());
        }
        path.remove(v.getId());
        memo.put(v.getId(), n);
        return n;
    }
}
