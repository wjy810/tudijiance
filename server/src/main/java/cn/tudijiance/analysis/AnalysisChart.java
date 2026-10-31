package cn.tudijiance.analysis;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 自助分析里保存的图表。config 是客户端定义的图表设置（JSON 文本）。 */
@Data
@TableName("analysis_chart")
public class AnalysisChart {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String config;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
