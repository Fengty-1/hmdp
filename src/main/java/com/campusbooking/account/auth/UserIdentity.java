package com.campusbooking.account.auth;

import com.campusbooking.account.model.Role;

public record UserIdentity(long userId, Role role) {}
