package cn.tudijiance.field;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
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
}
