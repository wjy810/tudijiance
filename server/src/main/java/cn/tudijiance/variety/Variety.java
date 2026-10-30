package cn.tudijiance.variety;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("variety")
public class Variety {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** 统一编号（标准允许实名或密码编号） */
    private String code;
    private String breeder;
    /** 生态型：SPRING 春性 / WEAK_SPRING 弱春性 / SEMI_WINTER 半冬性 / WINTER 冬性 */
    private String ecotype;
    /** 品质类型：STRONG 强筋 / MEDIUM_STRONG 中强筋 / MEDIUM 中筋 / WEAK 弱筋 */
    private String qualityType;
    /** 对照类型：空 不是对照 / YIELD 丰产对照 / QUALITY 优质对照 / RESISTANCE 抗性对照 */
    private String controlType;
    private String remark;
    private LocalDateTime createdAt;
}
