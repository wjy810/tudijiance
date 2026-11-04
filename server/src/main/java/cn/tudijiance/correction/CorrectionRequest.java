package cn.tudijiance.correction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 更正申请：学生发现录错了，提交改成什么、为什么，老师审核通过后才改数据 */
@Data
@TableName("correction_request")
public class CorrectionRequest {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long observationId;
    private Long trialId;
    /** UPDATE 改值 / DELETE 删掉 */
    private String kind;
    /** 申请时数据的版本 */
    private Integer baseVersion;
    private String oldDisplay;
    private LocalDate oldObservedOn;
    private LocalDate observedOn;
    private BigDecimal valueNum;
    private String valueText;
    private LocalDate valueDate;
    private String note;
    private String newDisplay;
    private String reason;
    /** PENDING / APPROVED / REJECTED / WITHDRAWN */
    private String status;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNote;
}
