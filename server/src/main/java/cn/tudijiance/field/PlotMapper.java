package cn.tudijiance.field;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface PlotMapper extends BaseMapper<Plot> {

    /** 一条 INSERT 插入多个小区（在当前事务里执行）。调用方控制每批的数量 */
    @Insert("""
            <script>
            INSERT INTO plot (field_id, row_no, col_no, code, kind, length_m, width_m) VALUES
            <foreach collection="plots" item="p" separator=",">
              (#{p.fieldId}, #{p.rowNo}, #{p.colNo}, #{p.code}, #{p.kind}, #{p.lengthM}, #{p.widthM})
            </foreach>
            </script>
            """)
    int insertAll(@Param("plots") List<Plot> plots);
}
