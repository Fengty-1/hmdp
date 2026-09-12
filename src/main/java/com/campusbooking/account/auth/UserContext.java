package com.campusbooking.account.auth;

import com.campusbooking.common.ApiException;

public final class UserContext {
    private static final ThreadLocal<UserIdentity> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(UserIdentity user) { CURRENT.set(user); }
    public static UserIdentity current() { return CURRENT.get(); }

    public static UserIdentity require() {
        UserIdentity user = CURRENT.get();
        if (user == null) throw ApiException.unauthorized();
        return user;
    }

    public static void clear() { CURRENT.remove(); }
}
