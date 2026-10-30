package cn.tudijiance.trait;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 性状定义，属性采用 NY/T 1301—2025 附录 D 的数据描述。 */
@Data
@TableName("trait_def")
public class TraitDef {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    /** PHENOLOGY / MORPHOLOGY / GROWTH / STRESS / DISEASE / LAB / YIELD */
    private String category;
    /** DATE / NUMBER / GRADE / OPTION / TEXT */
    private String dataType;
    private String unit;
    private Integer precisionDigits;
    /** 多个重复的汇总方法：NONE / AVG / MAX / MIN / MODE */
    private String aggMethod;
    private String surveyTime;
    private String stdClause;
    private Boolean isStandard;
    private Boolean isComputed;
    private Boolean enabled;
    private Integer sortOrder;
    private String description;
}
