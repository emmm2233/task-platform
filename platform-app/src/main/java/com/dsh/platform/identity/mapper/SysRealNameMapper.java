package com.dsh.platform.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsh.platform.identity.entity.SysRealName;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysRealNameMapper extends BaseMapper<SysRealName> {

    @Select("SELECT * FROM sys_real_name WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    SysRealName selectLatestByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM sys_real_name WHERE id_card_hash = #{idCardHash} LIMIT 1")
    SysRealName selectByIdCardHash(@Param("idCardHash") String idCardHash);
}