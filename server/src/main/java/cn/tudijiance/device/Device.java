package cn.tudijiance.device;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 传感器设备。code 同时是 MQTT 主题里的设备名：tudi/devices/{code}/up */
@Data
@TableName("device")
public class Device {

    public static final String TYPE_SOIL_3IN1 = "SOIL_3IN1";

    public static final String STATUS_NEVER = "NEVER";
    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String deviceType;
    private Long fieldId;
    /** 在平面图上的位置（行、列坐标，可以是小数，表示落在两个小区之间） */
    private BigDecimal posRow;
    private BigDecimal posCol;
    private Integer depthCm;
    private String tokenHash;
    /** true 时由模拟器产生数据，用于硬件到货前的开发和演示 */
    private Boolean simulated;
    private String status;
    private LocalDateTime lastSeenAt;
    private String remark;
    private LocalDateTime createdAt;
}
