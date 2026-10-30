package cn.tudijiance.observation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 录入的一条数据：某个种植记录（试验 + 小区）在某天某个项目的值。 */
@Data
@TableName("observation")
public class Observation {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_IMPORT = "IMPORT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientUuid;
    private Long plantingId;
    private Long traitId;
    private LocalDate observedOn;
    private BigDecimal valueNum;
    private String valueText;
    private LocalDate valueDate;
    private String note;
    private String source;
    private Long recordedBy;
    private LocalDateTime recordedAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private Integer version;
    private Boolean deleted;
}
