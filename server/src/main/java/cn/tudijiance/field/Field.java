package cn.tudijiance.field;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 实验田：划分成 rowCount × colCount 个小区的地块。 */
@Data
@TableName("field")
public class Field {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String location;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal altitudeM;
    private BigDecimal areaMu;
    private Integer rowCount;
    private Integer colCount;
    private String soilType;
    private Boolean irrigated;
    private String previousCrop;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
