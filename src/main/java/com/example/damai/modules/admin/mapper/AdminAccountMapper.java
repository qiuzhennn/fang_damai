package com.example.damai.modules.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.admin.entity.AdminAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员账号数据访问接口。
 */
@Mapper
public interface AdminAccountMapper extends BaseMapper<AdminAccount> {
}