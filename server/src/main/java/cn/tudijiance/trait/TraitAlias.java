package cn.tudijiance.trait;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 录入项目的表头别名：历史表格里这样写的表头对应这个项目。alias 是规范化后的写法，见 {@link AliasText}。 */
@Data
@TableName("trait_alias")
public class TraitAlias {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long traitId;
    private String alias;
    private LocalDateTime createdAt;
}
