package cn.tudijiance.breeding;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.tudijiance.field.Field;
import cn.tudijiance.field.FieldLayout;
import cn.tudijiance.field.FieldService;
import cn.tudijiance.field.Plot;

/**
 * 小区块在哪：哪块地块、哪个区块、几号小区块。按地块缓存区块布局，避免一个品系的每个位置都解析一次布局。
 */
final class Places {

    record Spot(Long fieldId, String fieldName, String blockId, String blockName, String plotCode) {

        String text() {
            StringBuilder sb = new StringBuilder(fieldName == null ? "" : fieldName);
            if (blockName != null) {
                sb.append(' ').append(blockName);
            }
            if (plotCode != null) {
                sb.append(' ').append(plotCode).append(" 号小区块");
            }
            return sb.toString().trim();
        }
    }

    private final FieldService fieldService;
    private final Map<Long, Field> fields;
    private final Map<Long, Map<Long, String[]>> blocksByField = new HashMap<>();
    private final Map<Long, Map<Long, Plot>> plotsByField = new HashMap<>();

    Places(FieldService fieldService, Map<Long, Field> fields) {
        this.fieldService = fieldService;
        this.fields = fields;
    }

    /** 一块地块的区块：小区块 id → {区块 id, 区块名称} */
    Map<Long, String[]> blocks(Long fieldId) {
        return blocksByField.computeIfAbsent(fieldId, id -> {
            Map<Long, String[]> map = new HashMap<>();
            Field f = field(id);
            FieldLayout layout = f == null ? null : fieldService.savedLayout(f);
            if (layout != null) {
                for (FieldLayout.Block b : layout.blocks()) {
                    if (b.inner() != null && b.inner().plots() != null) {
                        b.inner().plots().values().forEach(pid -> map.put(pid, new String[] {b.id(), b.name()}));
                    }
                }
            }
            return map;
        });
    }

    /** 区块里的小区块 */
    List<Long> plotsInBlock(Long fieldId, String blockId) {
        return blocks(fieldId).entrySet().stream().filter(e -> e.getValue()[0].equals(blockId))
                .map(Map.Entry::getKey).sorted().toList();
    }

    String blockName(Long fieldId, String blockId) {
        Field f = field(fieldId);
        FieldLayout layout = f == null ? null : fieldService.savedLayout(f);
        if (layout == null || blockId == null) {
            return null;
        }
        return layout.blocks().stream().filter(b -> b.id().equals(blockId)).map(FieldLayout.Block::name)
                .findFirst().orElse(null);
    }

    Plot plot(Long fieldId, Long plotId) {
        return plotsByField.computeIfAbsent(fieldId, id -> {
            Map<Long, Plot> map = new HashMap<>();
            fieldService.plots(id).forEach(p -> map.put(p.getId(), p));
            return map;
        }).get(plotId);
    }

    Spot spot(Long fieldId, Long plotId) {
        Field f = field(fieldId);
        String[] block = plotId == null ? null : blocks(fieldId).get(plotId);
        Plot p = plotId == null ? null : plot(fieldId, plotId);
        return new Spot(fieldId, f == null ? null : f.getName(), block == null ? null : block[0],
                block == null ? null : block[1], p == null ? null : p.getCode());
    }

    /** 几个小区块合起来说：同一个区块的全部小区块写"东地 区块1"，否则列出编号 */
    String describe(Long fieldId, Collection<Long> plotIds) {
        Field f = field(fieldId);
        String fieldName = f == null ? "" : f.getName();
        if (plotIds.isEmpty()) {
            return fieldName;
        }
        if (plotIds.size() == 1) {
            return spot(fieldId, plotIds.iterator().next()).text();
        }
        Map<String, List<Long>> byBlock = new java.util.LinkedHashMap<>();
        for (Long id : plotIds) {
            String[] b = blocks(fieldId).get(id);
            byBlock.computeIfAbsent(b == null ? "" : b[1], k -> new java.util.ArrayList<>()).add(id);
        }
        StringBuilder sb = new StringBuilder(fieldName);
        byBlock.forEach((name, ids) -> {
            sb.append(' ').append(name);
            if (ids.size() < plotsInBlockByName(fieldId, name)) {
                sb.append(' ').append(ids.stream().map(id -> {
                    Plot p = plot(fieldId, id);
                    return p == null || p.getCode() == null ? String.valueOf(id) : p.getCode();
                }).collect(java.util.stream.Collectors.joining("、"))).append(" 号");
            }
        });
        return sb.toString().trim();
    }

    private int plotsInBlockByName(Long fieldId, String blockName) {
        return (int) blocks(fieldId).values().stream().filter(b -> b[1].equals(blockName)).count();
    }

    private Field field(Long id) {
        Field f = fields.get(id);
        return f != null ? f : fieldService.find(id);
    }
}
