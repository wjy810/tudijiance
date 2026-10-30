package cn.tudijiance.trial;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 对照关系：平面图上用虚线连接的一组小区。 */
@Data
@TableName("control_link")
public class ControlLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trialId;
    private String label;
    private LocalDateTime createdAt;
}
