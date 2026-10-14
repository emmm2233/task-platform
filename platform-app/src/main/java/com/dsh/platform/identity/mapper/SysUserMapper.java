package com.dsh.platform.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsh.platform.identity.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * <p>⛔ 只提供按 {@code phoneHash} 查询的方法 —— 不提供按明文手机号查询，
 * 避免后来者绕开盲索引写出明文检索。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE phone_hash = #{phoneHash} LIMIT 1")
    SysUser selectByPhoneHash(@Param("phoneHash") String phoneHash);

    @Select("SELECT * FROM sys_user WHERE user_no = #{userNo} LIMIT 1")
    SysUser selectByUserNo(@Param("userNo") String userNo);
}