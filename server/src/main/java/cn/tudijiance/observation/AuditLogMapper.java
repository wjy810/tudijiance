package cn.tudijiance.observation;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
