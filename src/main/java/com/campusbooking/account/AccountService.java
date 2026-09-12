package com.campusbooking.account;

import com.campusbooking.account.auth.AccountRedisStore;
import com.campusbooking.account.auth.UserIdentity;
import com.campusbooking.account.dto.AccountViews.CodeReceipt;
import com.campusbooking.account.dto.AccountViews.LoginResult;
import com.campusbooking.account.dto.AccountViews.Profile;
import com.campusbooking.account.mapper.AccountMapper;
import com.campusbooking.account.model.Account;
import com.campusbooking.account.model.Role;
import com.campusbooking.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private final SecureRandom random = new SecureRandom();
    private final AccountMapper mapper;
    private final AccountRedisStore store;
    private final AccountProperties properties;

    public AccountService(AccountMapper mapper, AccountRedisStore store,
                          AccountProperties properties, Environment environment) {
        this.mapper = mapper;
        this.store = store;
        this.properties = properties;
        if (properties.devSmsEnabled() && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("SMS simulation requires the dev or test profile");
        }
    }

    public CodeReceipt sendCode(String phone) {
        if (!properties.devSmsEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_NOT_CONFIGURED", "短信服务尚未配置");
        }
        String code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
        if (!store.issueCode(phone, code)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CODE_RATE_LIMITED", "验证码请求过于频繁，请稍后再试");
        }
        log.info("Development verification code issued; delivery=DEVELOPMENT_SIMULATION");
        return new CodeReceipt("DEVELOPMENT_SIMULATION", code, properties.codeTtl().toSeconds());
    }

    public LoginResult login(String phone, String code) {
        if (!store.consumeCode(phone, code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "验证码错误、已使用或已过期");
        }
        Account account = mapper.findByPhone(phone);
        if (account == null) {
            account = new Account();
            account.setPhone(phone);
            account.setNickname("同学" + phone.substring(7));
            account.setRole(Role.STUDENT);
            try {
                // 单条写入自动提交；Token 只能在用户已持久化后签发。
                mapper.insert(account);
            } catch (DuplicateKeyException exception) {
                // 数据库唯一键是最后防线，另一登录已创建时复用同一用户。
                account = mapper.findByPhone(phone);
                if (account == null) throw exception;
            }
        }
        String token = store.createToken(new UserIdentity(account.getId(), account.getRole()));
        log.info("Account login succeeded userId={}", account.getId());
        return new LoginResult(token, "Bearer", properties.tokenTtl().toSeconds(), Profile.from(account));
    }

    public Profile currentUser(long userId) { return Profile.from(requireAccount(userId)); }

    public Profile updateProfile(long userId, String nickname) {
        Account account = requireAccount(userId);
        Account changes = new Account();
        changes.setId(userId);
        changes.setNickname(nickname.strip());
        mapper.updateById(changes);
        account.setNickname(changes.getNickname());
        return Profile.from(account);
    }

    public void logout(long userId, String token) {
        store.deleteToken(token);
        log.info("Account logout succeeded userId={}", userId);
    }

    private Account requireAccount(long userId) {
        Account account = mapper.selectById(userId);
        if (account == null) throw ApiException.unauthorized();
        return account;
    }
}
