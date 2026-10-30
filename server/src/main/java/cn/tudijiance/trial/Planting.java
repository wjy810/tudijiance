package cn.tudijiance.trial;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 种植记录：某个试验里，某个小区种的品种和所属重复。 */
@Data
@TableName("planting")
public class Planting {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trialId;
    private Long plotId;
    private Long varietyId;
    private Integer replicateNo;
}
