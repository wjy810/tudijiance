package cn.tudijiance.trial;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 试验记载本（按《河南省小麦品种试验记载本》）：封面、试验设计、栽培管理、产量计算参数、气象资料、品种评价。
 * 三个内容列都是 JSON 文本，字段定义在客户端 recordBook.ts。
 */
@Data
@TableName("trial_book")
public class TrialBook {

    @TableId(type = IdType.INPUT)
    private Long trialId;
    private String info;
    private String weather;
    private String evaluations;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
