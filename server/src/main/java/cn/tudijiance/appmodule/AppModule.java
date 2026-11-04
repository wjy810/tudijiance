package cn.tudijiance.appmodule;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 功能模块：内置模块可以停用，链接模块可以增删改（见 V9__app_module.sql） */
@Data
@TableName("app_module")
public class AppModule {

    public static final String BUILTIN = "BUILTIN";
    public static final String LINK = "LINK";

    @TableId(type = IdType.INPUT)
    private String code;
    private String name;
    private String description;
    private String kind;
    private Boolean required;
    private Boolean enabled;
    private String url;
    /** 链接模块给哪些角色看，逗号分隔，例如 TEACHER,STUDENT */
    private String roles;
    private Integer sortOrder;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    public boolean isOn() {
        return Boolean.TRUE.equals(enabled) || Boolean.TRUE.equals(required);
    }

    static LocalDateTime now(java.time.Clock clock) {
        return LocalDateTime.now(clock);
    }
}
