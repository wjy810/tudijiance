package cn.tudijiance.device;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 测量项。阶段一启用土壤温度、湿度、pH；其余是给以后加传感器预留的，enabled = false。 */
@Data
@TableName("sensor_metric")
public class SensorMetric {

    @TableId(type = IdType.INPUT)
    private String code;
    private String name;
    private String unit;
    private BigDecimal minValid;
    private BigDecimal maxValid;
    private Integer precisionDigits;
    private Boolean enabled;
    private Integer sortOrder;
}
