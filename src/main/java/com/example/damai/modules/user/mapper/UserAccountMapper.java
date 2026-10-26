package com.example.damai.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 普通用户账号数据访问接口。
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}