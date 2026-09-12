package com.campusbooking.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusbooking.account.model.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
    @Select("SELECT id, phone, nickname, role FROM account_user WHERE phone = #{phone}")
    Account findByPhone(String phone);
}
