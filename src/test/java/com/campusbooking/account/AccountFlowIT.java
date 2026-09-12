package com.campusbooking.account;

import com.campusbooking.account.auth.AccountRedisStore;
import com.campusbooking.account.auth.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "app.account.dev-sms-enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AccountFlowIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("campus_booking_test").withUsername("test").withPassword("test");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.8"))
            .withExposedPorts(6379);
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.2.5-management"));

    @DynamicPropertySource
    static void configureIsolatedServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired HealthEndpoint health;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void clearOnlyTestcontainerData() {
        jdbc.update("DELETE FROM account_user");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void noUserContextRemains() {
        try { assertThat(UserContext.current()).isNull(); }
        finally { UserContext.clear(); }
    }

    @Test
    void applicationStartsAndAllThreeServicesAreReachable() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP").doesNotContain("password");
        CompositeHealth aggregate = (CompositeHealth) health.health();
        for (String component : new String[]{"db", "redis", "rabbit"}) {
            assertThat(aggregate.getComponents().get(component).getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void loginProfileRefreshLogoutFormOneCompleteLifecycle(CapturedOutput output) throws Exception {
        String phone = "13800000001";
        String code = issue(phone);
        JsonNode login = login(phone, code);
        String token = login.path("token").asText();
        assertThat(login.path("user").path("role").asText()).isEqualTo("STUDENT");
        assertThat(login.path("expiresInSeconds").asLong()).isEqualTo(1800);
        assertThat(countUsers()).isEqualTo(1);
        String tokenKey = AccountRedisStore.tokenKey(token);
        redis.expire(tokenKey, Duration.ofSeconds(3));
        mvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value(phone));
        assertThat(redis.getExpire(tokenKey, TimeUnit.SECONDS)).isGreaterThan(1700);
        mvc.perform(patch("/api/account/me").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"校园同学\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("校园同学"));
        mvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.nickname").value("校园同学"));
        mvc.perform(post("/api/account/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        assertThat(redis.hasKey(tokenKey)).isFalse();
        assertThat(output.getAll()).doesNotContain(code, token);
    }

    @Test
    void codeCanOnlyBeUsedOnceAndWrongAttemptDoesNotConsumeValidCodeImmediately() throws Exception {
        String phone = "13800000002";
        String code = issue(phone);
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(phone, wrongCode(code))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        login(phone, code);
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(phone, code)))
                .andExpect(status().isBadRequest());
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void fiveWrongAttemptsInvalidateCodeWithoutExtendingLifetime() throws Exception {
        String phone = "13800000003";
        String code = issue(phone);
        String key = AccountRedisStore.codeKey(phone);
        redis.expire(key, Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(phone, wrongCode(code))))
                    .andExpect(status().isBadRequest());
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isLessThanOrEqualTo(30);
        }
        assertThat(redis.hasKey(key)).isFalse();
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(phone, code)))
                .andExpect(status().isBadRequest());
        assertThat(countUsers()).isZero();
    }

    @Test
    void resendDuringCooldownIsRejectedWithoutReplacingCode() throws Exception {
        String phone = "13800000004";
        String code = issue(phone);
        mvc.perform(post("/api/account/code").contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isTooManyRequests());
        assertThat(redis.opsForHash().get(AccountRedisStore.codeKey(phone), "code")).isEqualTo(code);
        login(phone, code);
    }

    @Test
    void expiredCodeAndExpiredTokenAreRejected() throws Exception {
        String phone = "13800000005";
        String code = issue(phone);
        expireAndWait(AccountRedisStore.codeKey(phone));
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(phone, code)))
                .andExpect(status().isBadRequest());
        assertThat(countUsers()).isZero();
        String token = login("13800000006", issue("13800000006")).path("token").asText();
        String tokenKey = AccountRedisStore.tokenKey(token);
        expireAndWait(tokenKey);
        mvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        assertThat(redis.hasKey(tokenKey)).isFalse();
    }

    @Test
    void concurrentLoginWithSameCodeCreatesExactlyOneSession() throws Exception {
        String phone = "13800000007";
        String code = issue(phone);
        String body = loginBody(phone, code);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            ArrayList<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                    int status = mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                    assertThat(UserContext.current()).isNull();
                    return status;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ArrayList<Integer> statuses = new ArrayList<>();
            for (Future<Integer> call : calls) statuses.add(call.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200, 400);
            assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        }
        assertThat(countUsers()).isEqualTo(1);
        // KEYS 仅在这个全新、数据量极小的测试 Redis 中检查登录态数量。
        assertThat(redis.keys("campus:account:token:*")).hasSize(1);
    }

    @Test
    void existingOrganizerRoleComesFromDatabaseAndRepeatedLoginReusesUser() throws Exception {
        String phone = "13900000000";
        jdbc.update("INSERT INTO account_user(phone, nickname, role) VALUES (?, ?, ?)", phone, "组织者", "ORGANIZER");
        JsonNode first = login(phone, issue(phone));
        assertThat(first.path("user").path("role").asText()).isEqualTo("ORGANIZER");
        expireAndWait("campus:account:cooldown:" + phone);
        JsonNode second = login(phone, issue(phone));
        assertThat(second.path("user").path("id")).isEqualTo(first.path("user").path("id"));
        assertThat(countUsers()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO account_user(phone, nickname, role) VALUES (?, ?, ?)",
                phone, "重复", "STUDENT")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void clientsCannotChooseRoleOrEditAnotherUsersProfile() throws Exception {
        String phone = "13800000008";
        String code = issue(phone);
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("phone", phone, "code", code, "role", "ORGANIZER"))))
                .andExpect(status().isBadRequest());
        String token = login(phone, code).path("token").asText();
        for (String body : new String[]{"{\"nickname\":\"修改\",\"role\":\"ORGANIZER\"}",
                "{\"nickname\":\"修改\",\"id\":999}", "{\"nickname\":\" \"}"}) {
            mvc.perform(patch("/api/account/me").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/account/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.role").value("STUDENT"));
    }

    @Test
    void anonymousProtectedRequestsAndMalformedPublicRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/account/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/logout")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/account/me").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"同学\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/code").contentType(MediaType.APPLICATION_JSON).content(phoneBody("bad")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("13800000009", "bad")))
                .andExpect(status().isBadRequest());
        assertThat(countUsers()).isZero();
    }

    private String issue(String phone) throws Exception {
        JsonNode receipt = data(mvc.perform(post("/api/account/code").contentType(MediaType.APPLICATION_JSON)
                        .content(phoneBody(phone))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn());
        assertThat(receipt.path("deliveryMode").asText()).isEqualTo("DEVELOPMENT_SIMULATION");
        return receipt.path("devCode").asText();
    }

    private JsonNode login(String phone, String code) throws Exception {
        return data(mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(phone, code))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String phoneBody(String phone) throws Exception { return json.writeValueAsString(Map.of("phone", phone)); }
    private String loginBody(String phone, String code) throws Exception {
        return json.writeValueAsString(Map.of("phone", phone, "code", code));
    }
    private int countUsers() { return jdbc.queryForObject("SELECT COUNT(*) FROM account_user", Integer.class); }
    private static String bearer(String token) { return "Bearer " + token; }
    private static String wrongCode(String code) { return code.equals("000000") ? "000001" : "000000"; }
    private void expireAndWait(String key) {
        redis.expire(key, Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
    }
}
