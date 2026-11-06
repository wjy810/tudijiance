package cn.tudijiance.variety;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 品种。lineKind 不为空的是育种用的品系（见 cn.tudijiance.breeding）：原始材料、杂交组合、选株后代，
 * 带编号、父母、状态；原来的品种比较只看 lineKind 为空或原始材料的。
 */
@Data
@TableName("variety")
public class Variety {

    public static final String FOUNDER = "FOUNDER";
    public static final String CROSS = "CROSS";
    public static final String SELECTION = "SELECTION";

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

    // ---------- 品系 ----------

    /** FOUNDER 原始材料 / CROSS 杂交组合 / SELECTION 选株后代；为空表示只是品种比较用的品种 */
    private String lineKind;
    /** 编号：建好后不变 */
    private String lineCode;
    /** 杂交组合的母本、父本 */
    private Long motherId;
    private Long fatherId;
    /** 选株后代：从哪个品系里选出来的 */
    private Long parentId;
    /** ACTIVE 在选 / KEPT 保留 / DROPPED 淘汰 / RELEASED 已育成 */
    private String lineStatus;
    /** 曾用名，用"、"隔开 */
    private String formerNames;
    /** 杂交、选株是在哪一季做的 */
    private Long originSeasonId;
    /** 选株：从哪块地块、哪个区块、哪个小区块选的 */
    private Long originFieldId;
    private String originBlockId;
    private Long originPlotId;
    /** 杂交组合：当年第几个；选株后代：从父本里选出的第几株 */
    private Integer seqNo;
    /** 杂交组合：杂交年份 */
    private Integer crossYear;
    /** 选株：田间挂牌号 */
    private String tagNo;

    public boolean isLine() {
        return lineKind != null;
    }
}
