package com.campusbooking.account;

import com.campusbooking.account.auth.AccountRedisStore;
import com.campusbooking.account.auth.UserIdentity;
import com.campusbooking.account.mapper.AccountMapper;
import com.campusbooking.account.model.Account;
import com.campusbooking.account.model.Role;
import com.campusbooking.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock AccountMapper mapper;
    @Mock AccountRedisStore store;
    AccountService service;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        service = new AccountService(mapper, store, properties(true), environment);
    }

    @Test
    void invalidCodeDoesNotAccessDatabaseOrCreateToken() {
        assertThatThrownBy(() -> service.login("13800000001", "123456"))
                .isInstanceOf(ApiException.class).hasMessageContaining("验证码");
        verifyNoInteractions(mapper);
        verify(store, never()).createToken(any());
    }

    @Test
    void newlyCreatedAccountAlwaysHasStudentRole() {
        when(store.consumeCode("13800000001", "123456")).thenReturn(true);
        doAnswer(invocation -> { invocation.getArgument(0, Account.class).setId(7L); return 1; })
                .when(mapper).insert(any(Account.class));
        when(store.createToken(new UserIdentity(7L, Role.STUDENT))).thenReturn("test-token");

        var result = service.login("13800000001", "123456");

        assertThat(result.user().role()).isEqualTo(Role.STUDENT);
        assertThat(result.user().id()).isEqualTo(7L);
        var order = inOrder(mapper, store);
        order.verify(store).consumeCode("13800000001", "123456");
        order.verify(mapper).findByPhone("13800000001");
        order.verify(mapper).insert(any(Account.class));
        order.verify(store).createToken(new UserIdentity(7L, Role.STUDENT));
    }

    @Test
    void concurrentAccountCreationReusesDatabaseUniqueWinner() {
        Account existing = account(Role.ORGANIZER);
        when(store.consumeCode("13800000001", "123456")).thenReturn(true);
        when(mapper.findByPhone("13800000001")).thenReturn(null, existing);
        when(mapper.insert(any(Account.class))).thenThrow(new DuplicateKeyException("duplicate phone"));
        when(store.createToken(new UserIdentity(7L, Role.ORGANIZER))).thenReturn("test-token");

        assertThat(service.login("13800000001", "123456").user().role()).isEqualTo(Role.ORGANIZER);
        verify(mapper, times(2)).findByPhone("13800000001");
    }

    @Test
    void profileUpdateOnlyWritesNickname() {
        when(mapper.selectById(7L)).thenReturn(account(Role.ORGANIZER));
        assertThat(service.updateProfile(7L, "  社团同学  ").nickname()).isEqualTo("社团同学");
        ArgumentCaptor<Account> changes = ArgumentCaptor.forClass(Account.class);
        verify(mapper).updateById(changes.capture());
        assertThat(changes.getValue().getRole()).isNull();
        assertThat(changes.getValue().getPhone()).isNull();
    }

    @Test
    void nonDevelopmentModeCannotSendOrReturnSimulatedCode() {
        AccountService disabled = new AccountService(mapper, store, properties(false), new MockEnvironment());
        assertThatThrownBy(() -> disabled.sendCode("13800000001"))
                .isInstanceOf(ApiException.class).hasMessage("短信服务尚未配置");
        verifyNoInteractions(store);
    }

    @Test
    void simulationCannotBeEnabledOutsideDevelopmentProfiles() {
        assertThatThrownBy(() -> new AccountService(mapper, store, properties(true), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AccountProperties properties(boolean enabled) {
        return new AccountProperties(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, Duration.ofMinutes(30), enabled);
    }

    private static Account account(Role role) {
        Account account = new Account();
        account.setId(7L);
        account.setPhone("13800000001");
        account.setNickname("同学0001");
        account.setRole(role);
        return account;
    }
}
