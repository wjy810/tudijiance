package cn.tudijiance.device;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface SensorReadingMapper extends BaseMapper<SensorReading> {

    /** 每台设备每个测量项的最新一条读数。 */
    @Select("""
            SELECT r.device_id, r.metric, r.metric_value, r.measured_at, r.out_of_range
            FROM sensor_reading r
            JOIN (SELECT device_id, metric, MAX(measured_at) AS latest
                  FROM sensor_reading GROUP BY device_id, metric) x
              ON r.device_id = x.device_id AND r.metric = x.metric AND r.measured_at = x.latest
            """)
    List<SensorReading> selectLatest();
}
