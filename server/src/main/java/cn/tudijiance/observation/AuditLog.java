package cn.tudijiance.observation;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 数据的录入、修改、删除记录。 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String entity;
    private Long entityId;
    private String action;
    private String oldValue;
    private String newValue;
    private Long userId;
    private LocalDateTime createdAt;
    /** 补充说明，例如"按李同学的更正申请：……" */
    private String note;
}
