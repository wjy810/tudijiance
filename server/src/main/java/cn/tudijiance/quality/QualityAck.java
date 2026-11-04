package cn.tudijiance.quality;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 老师核实过、确认没错的质量问题 */
@Data
@TableName("quality_ack")
public class QualityAck {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trialId;
    private String issueKey;
    private String note;
    private Long ackedBy;
    private LocalDateTime ackedAt;
}
