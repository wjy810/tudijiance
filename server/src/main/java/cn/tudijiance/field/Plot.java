package cn.tudijiance.field;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 小区：实验田网格中的一格。行号、列号从 1 开始。 */
@Data
@TableName("plot")
public class Plot {

    public static final String KIND_PLOT = "PLOT";
    public static final String KIND_GUARD = "GUARD";
    public static final String KIND_PATH = "PATH";
    /** 空地：区块里不种的格子 */
    public static final String KIND_BLANK = "BLANK";

    /** 布局里格子的类型 → 小区类型：种植小区是试验小区，道路是走道 */
    public static String kindOfCell(String cellKind) {
        if (cellKind == null || FieldLayout.PLANT.equals(cellKind)) {
            return KIND_PLOT;
        }
        return switch (cellKind) {
            case FieldLayout.ROAD -> KIND_PATH;
            case FieldLayout.GUARD -> KIND_GUARD;
            case FieldLayout.BLANK -> KIND_BLANK;
            default -> KIND_PLOT;
        };
    }

    /** 小区类型 → 布局里格子的类型 */
    public static String cellKindOf(String plotKind) {
        if (plotKind == null) {
            return FieldLayout.PLANT;
        }
        return switch (plotKind) {
            case KIND_PATH -> FieldLayout.ROAD;
            case KIND_GUARD -> FieldLayout.GUARD;
            case KIND_BLANK -> FieldLayout.BLANK;
            default -> FieldLayout.PLANT;
        };
    }

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fieldId;
    private Integer rowNo;
    private Integer colNo;
    private String code;
    private String kind;
    private BigDecimal lengthM;
    private BigDecimal widthM;
    private Integer plantingRows;
    private BigDecimal rowSpacingCm;

    /** 试验记载本里填的收获面积（m²），不存库，读试验时由 TrialContextLoader 填上 */
    @TableField(exist = false)
    private BigDecimal harvestAreaM2;

    /** 计产面积（m²）：记载本填了收获面积用它，否则按长 × 宽；都没有时为 null */
    public BigDecimal areaM2() {
        if (harvestAreaM2 != null && harvestAreaM2.signum() > 0) {
            return harvestAreaM2;
        }
        return lengthM == null || widthM == null ? null : lengthM.multiply(widthM);
    }
}
