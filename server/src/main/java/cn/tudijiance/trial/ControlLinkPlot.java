package cn.tudijiance.trial;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("control_link_plot")
public class ControlLinkPlot {

    private Long linkId;
    private Long plotId;
    private Boolean isControl;
}
