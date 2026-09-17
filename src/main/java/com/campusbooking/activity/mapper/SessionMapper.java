package com.campusbooking.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusbooking.activity.model.ActivitySession;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SessionMapper extends BaseMapper<ActivitySession> {}
