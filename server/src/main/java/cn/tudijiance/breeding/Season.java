package cn.tudijiance.breeding;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 季：一次种植周期，如"2025-2026 年度大田""2026 夏繁"。一年可以有两季。 */
@Data
@TableName("season")
public class Season {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String remark;
    private LocalDateTime createdAt;

    /** 这一季算哪一年：有结束日期按结束那年（2024-2025 年度大田算 2025），否则按开始那年 */
    public int yearOf() {
        return (endDate != null ? endDate : startDate).getYear();
    }
}
