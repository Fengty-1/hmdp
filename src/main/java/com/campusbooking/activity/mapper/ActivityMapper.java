package com.campusbooking.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusbooking.activity.model.Activity;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
    @Select("SELECT * FROM activity WHERE id = #{id} FOR UPDATE")
    Activity lockById(long id);
}
