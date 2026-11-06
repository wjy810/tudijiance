package cn.tudijiance.trial;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 试验：某一年在某块实验田上的布局（种什么品种、怎么设重复和对照）。 */
@Data
@TableName("trial")
public class Trial {

    /** 育种地块自带的试验（每块地块一个，跟着地块建、删）；不在品种比较里出现 */
    public static final String BREEDING = "BREEDING";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fieldId;
    private Integer seasonYear;
    private String name;
    /** REGIONAL 区域试验 / PRODUCTION 生产试验 / COMPARISON 品比试验 / OBSERVATION 观察圃 / BREEDING 育种地块 */
    private String trialType;
    /** 麦区 Q1–Q11 */
    private String wheatZone;
    private Integer replicateCount;
    private String remark;
    /** 计产用的收获面积（m²），由试验记载本的产量计算参数算出；为空时按小区长 × 宽 */
    private BigDecimal harvestAreaM2;
    private LocalDateTime createdAt;
}
