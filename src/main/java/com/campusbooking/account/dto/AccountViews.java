package com.campusbooking.account.dto;

import com.campusbooking.account.model.Account;
import com.campusbooking.account.model.Role;

public final class AccountViews {
    private AccountViews() {}

    public record Profile(Long id, String phone, String nickname, Role role) {
        public static Profile from(Account account) {
            return new Profile(account.getId(), account.getPhone(), account.getNickname(), account.getRole());
        }
    }

    public record CodeReceipt(String deliveryMode, String devCode, long expiresInSeconds) {}

    public record LoginResult(String token, String tokenType, long expiresInSeconds, Profile user) {}
}
