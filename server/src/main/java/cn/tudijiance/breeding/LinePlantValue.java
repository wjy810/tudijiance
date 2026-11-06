package cn.tudijiance.breeding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 选出时的单株数据：选株时当场量的这一株，记在子代名下，不进大田的汇总和统计 */
@Data
@TableName("line_plant_value")
public class LinePlantValue {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long lineId;
    private Long traitId;
    private BigDecimal valueNum;
    private String valueText;
    private LocalDate valueDate;
    private Long recordedBy;
    private LocalDateTime recordedAt;
}
