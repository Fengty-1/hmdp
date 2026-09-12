package com.campusbooking.account.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("account_user")
public class Account {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String nickname;
    private Role role;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
}
