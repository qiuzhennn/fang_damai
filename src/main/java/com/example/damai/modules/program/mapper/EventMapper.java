package com.example.damai.modules.program.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.program.entity.Event;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演出数据访问接口，负责操作 tr_event 表。
 */
@Mapper
public interface EventMapper extends BaseMapper<Event> {

}
