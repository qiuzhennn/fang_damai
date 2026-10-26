package com.example.damai.modules.program.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.program.entity.EventSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 场次数据访问接口。
 */
@Mapper
public interface EventSessionMapper extends BaseMapper<EventSession> {

    @Select("""
    SELECT *
    FROM tr_event_session
    WHERE id = #{id}
    FOR UPDATE
    """)
    EventSession selectByIdForUpdate(@Param("id") Long id);


}