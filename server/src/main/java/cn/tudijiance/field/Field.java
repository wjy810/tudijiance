package cn.tudijiance.field;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** 区块布局（JSON，见 FieldLayout）；为空表示还是按行列建的老网格。不直接返回给客户端，详情里给解析好的 */
    @JsonIgnore
    private String layout;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
