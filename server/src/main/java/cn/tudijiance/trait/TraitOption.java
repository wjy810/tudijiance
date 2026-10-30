package cn.tudijiance.trait;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 分级或选项的取值，例如倒伏程度 3 级 = 倾斜 30°–45°。 */
@Data
@TableName("trait_option")
public class TraitOption {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long traitId;
    private String valueCode;
    private String label;
    private String description;
    private Integer sortOrder;
}
