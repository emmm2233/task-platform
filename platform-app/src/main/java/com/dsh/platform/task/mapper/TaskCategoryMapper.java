package com.dsh.platform.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsh.platform.task.entity.TaskCategory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskCategoryMapper extends BaseMapper<TaskCategory> {

    @Select("SELECT * FROM task_category WHERE code = #{code} LIMIT 1")
    TaskCategory selectByCode(@Param("code") String code);

    @Select("SELECT * FROM task_category WHERE parent_id = #{parentId} AND enabled = 1 ORDER BY sort_no, id")
    List<TaskCategory> selectChildren(@Param("parentId") Long parentId);

    @Select("SELECT * FROM task_category WHERE level = #{level} AND enabled = 1 ORDER BY sort_no, id")
    List<TaskCategory> selectByLevel(@Param("level") int level);
}