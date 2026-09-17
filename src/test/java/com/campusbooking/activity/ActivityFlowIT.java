package com.campusbooking.activity;

import com.campusbooking.account.auth.AccountRedisStore;
import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.auth.UserIdentity;
import com.campusbooking.account.model.Role;
import com.campusbooking.activity.mapper.ActivityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/schema.sql",
                "app.account.dev-sms-enabled=true",
                "logging.level.com.campusbooking.activity=DEBUG"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivityFlowIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("campus_activity_test").withUsername("test").withPassword("test");
    @Container static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.8")).withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.2.5-management"));

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
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
    @Autowired RedissonClient redisson;
    @Autowired AccountRedisStore accounts;
    @Autowired TestRestTemplate http;
    @MockitoSpyBean ActivityDetailCache cache;
    @MockitoSpyBean ActivityMapper activities;
    @MockitoSpyBean SessionRuntimeStore runtime;
    @MockitoSpyBean ActivityGeoStore geo;
    String owner;
    String other;
    String student;

    @BeforeEach
    void resetIsolatedData() {
        jdbc.update("DELETE FROM activity_session");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM activity_location");
        jdbc.update("DELETE FROM account_user");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        owner = identity("13900000001", Role.ORGANIZER);
        other = identity("13900000002", Role.ORGANIZER);
        student = identity("13800000001", Role.STUDENT);
    }

    @AfterEach
    void cleanContext() {
        try { assertThat(UserContext.current()).isNull(); }
        finally { UserContext.clear(); }
    }

    @Test
    void startsWithRealHttpAndPublishesDiscoverableActivity() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        mvc.perform(get("/api/activities/" + fixture.activity).header("Authorization", student))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/organizer/activities/" + fixture.activity).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessions[0].status").value("DRAFT"));
        publish(fixture, owner, 200);
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", student);
        var response = http.exchange("/api/activities/" + fixture.activity, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(response.getBody()).path("data").path("sessions").get(0).path("capacity").asInt())
                .isEqualTo(10);
        mvc.perform(get("/api/activities").header("Authorization", student))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        String key = SessionRuntimeStore.runtimeKey(fixture.session);
        assertThat(redis.opsForHash().get(key, "available")).isEqualTo("10");
        assertThat(redis.opsForHash().get(key, "enabled")).isEqualTo("1");
        long ends = Long.parseLong((String) redis.opsForHash().get(key, "sessionEndAt"));
        long expires = Long.parseLong((String) redis.opsForHash().get(key, "expireAt"));
        assertThat(expires - ends).isEqualTo(Duration.ofHours(24).toMillis());
        assertThat(expireAt(key)).isEqualTo(expires);
        assertThat(jdbc.queryForObject("SELECT remaining_capacity FROM activity_session WHERE id=?",
                Integer.class, fixture.session)).isEqualTo(10);
    }

    @Test
    void rejectsAnonymousStudentsAndCrossOrganizerManagement() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        mvc.perform(get("/api/activities")).andExpect(status().isUnauthorized());
        publish(fixture, student, 403);
        publish(fixture, other, 403);
        mvc.perform(patch("/api/organizer/activities/" + fixture.activity).header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON).content(display("越权"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/organizer/activities/" + fixture.activity).header("Authorization", other))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/organizer/locations").header("Authorization", student)
                .contentType(MediaType.APPLICATION_JSON).content(location(116, 39))).andExpect(status().isForbidden());
        Fixture second = fixture(116.4, 39.9);
        mvc.perform(post("/api/organizer/activities/" + second.activity + "/sessions/" + fixture.session + "/publish")
                .header("Authorization", owner)).andExpect(status().isNotFound());
    }

    @Test
    void validatesCoordinatesCapacityTimePageAndUnknownFields() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        mvc.perform(post("/api/organizer/locations").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(location(181, 39))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/organizer/activities/" + fixture.activity + "/sessions")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(sessionBody(0)))
                .andExpect(status().isBadRequest());
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String reversed = json.writeValueAsString(Map.of("capacity", 10, "registrationStartAt", now.plusSeconds(600),
                "registrationEndAt", now.plusSeconds(300), "startAt", now.plusSeconds(900), "endAt", now.plusSeconds(1200)));
        mvc.perform(put(sessionPath(fixture)).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(reversed)).andExpect(status().isBadRequest());
        for (String path : List.of("/api/activities?page=0", "/api/activities?size=51",
                "/api/activities?page=bad", "/api/activities/-1",
                "/api/activities/nearby?longitude=NaN&latitude=39", "/api/activities/nearby?longitude=116",
                "/api/activities/nearby?longitude=116&latitude=39&radiusKm=51")) {
            mvc.perform(get(path).header("Authorization", student)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/organizer/activities/" + fixture.activity).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"description\":\"d\",\"locationId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void draftEditsPublishLatestConfigurationAndFreezeAfterward() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        mvc.perform(put(sessionPath(fixture)).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(sessionBody(7))).andExpect(status().isOk());
        assertThat(redis.hasKey(SessionRuntimeStore.runtimeKey(fixture.session))).isFalse();
        publish(fixture, owner, 200);
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(fixture.session), "capacity")).isEqualTo("7");
        mvc.perform(put(sessionPath(fixture)).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(sessionBody(11)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_FROZEN"));
        assertThat(jdbc.queryForObject("SELECT capacity FROM activity_session WHERE id=?", Integer.class, fixture.session))
                .isEqualTo(7);
    }

    @Test
    void syncFailureLeavesCommittedFrozenConfigurationAndExplicitRetryWorks() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM activity_session WHERE id=?", String.class, fixture.session))
                    .isEqualTo("PUBLISHED");
            throw new DataAccessResourceFailureException("simulated unavailable");
        }).when(runtime).initialize(any());
        JsonNode failed = publish(fixture, owner, 503);
        assertThat(failed.path("data").path("runtimeReady").asBoolean()).isFalse();
        assertThat(failed.path("message").asText()).contains("配置已保存", "暂不可报名");
        assertThat(redis.hasKey(SessionRuntimeStore.runtimeKey(fixture.session))).isFalse();
        Instant expiry = jdbc.queryForObject("SELECT runtime_expire_at FROM activity_session WHERE id=?",
                java.sql.Timestamp.class, fixture.session).toInstant();
        doCallRealMethod().when(runtime).initialize(any());
        publish(fixture, owner, 200);
        assertThat(expireAt(SessionRuntimeStore.runtimeKey(fixture.session))).isEqualTo(expiry.toEpochMilli());
    }

    @Test
    void repeatedPublishPreservesAvailableUsersPendingAndAbsoluteExpiry() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        String key = SessionRuntimeStore.runtimeKey(fixture.session);
        long expiry = expireAt(key);
        redis.opsForHash().put(key, "available", "8");
        String users = SessionRuntimeStore.usersKey(fixture.session);
        String pending = "registration:request:test-request";
        redis.opsForHash().put(users, "123", "test-request");
        redis.expireAt(users, Instant.ofEpochMilli(expiry));
        redis.opsForHash().putAll(pending, Map.of("status", "PENDING", "sessionId", "" + fixture.session));
        redis.expireAt(pending, Instant.ofEpochMilli(expiry));
        publish(fixture, owner, 200);
        assertThat(redis.opsForHash().get(key, "available")).isEqualTo("8");
        assertThat(redis.opsForHash().get(users, "123")).isEqualTo("test-request");
        assertThat(redis.opsForHash().get(pending, "status")).isEqualTo("PENDING");
        for (String value : List.of(key, users, pending)) assertThat(expireAt(value)).isEqualTo(expiry);
    }

    @Test
    void initializationConflictsDoNotOverwriteAnyExistingState() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        String key = SessionRuntimeStore.runtimeKey(fixture.session);
        redis.opsForValue().set(key, "wrong-type");
        publish(fixture, owner, 503);
        assertThat(redis.opsForValue().get(key)).isEqualTo("wrong-type");
        redis.delete(key);
        publish(fixture, owner, 200);
        redis.opsForHash().put(key, "capacity", "999");
        var before = redis.opsForHash().entries(key);
        publish(fixture, owner, 503);
        assertThat(redis.opsForHash().entries(key)).isEqualTo(before);
    }

    @Test
    void missingRuntimeAfterOpeningCannotBeRebuiltFromMysqlCapacity() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        String key = SessionRuntimeStore.runtimeKey(fixture.session);
        redis.delete(key);
        // 仅在隔离测试修改时间，模拟已开放但运行键丢失；正式 API 禁止发布后改时间。
        jdbc.update("UPDATE activity_session SET registration_start_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), fixture.session);
        publish(fixture, owner, 503);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT remaining_capacity FROM activity_session WHERE id=?",
                Integer.class, fixture.session)).isEqualTo(10);
    }

    @Test
    void missingRuntimeWithExistingUserReservationsIsRejectedEvenBeforeOpening() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        redis.opsForHash().put(SessionRuntimeStore.usersKey(fixture.session), "123", "already-accepted");
        publish(fixture, owner, 503);
        assertThat(redis.hasKey(SessionRuntimeStore.runtimeKey(fixture.session))).isFalse();
    }

    @Test
    void cacheHitsNegativeCachingAndRandomTtlAvoidRepeatedDatabaseReads() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        clearInvocations(activities);
        read(fixture.activity, 200);
        read(fixture.activity, 200);
        verify(activities, times(1)).selectById(fixture.activity);
        assertThat(redis.getExpire(ActivityDetailCache.key(fixture.activity), TimeUnit.SECONDS)).isBetween(598L, 720L);
        long missing = 9999999;
        read(missing, 404);
        read(missing, 404);
        verify(activities, times(1)).selectById(missing);
        assertThat(redis.opsForValue().get(ActivityDetailCache.key(missing))).isEqualTo("null");
        assertThat(redis.getExpire(ActivityDetailCache.key(missing), TimeUnit.SECONDS)).isBetween(28L, 30L);
        redis.delete(ActivityDetailCache.key(fixture.activity));
        read(fixture.activity, 200);
        verify(activities, times(2)).selectById(fixture.activity);
    }

    @Test
    void concurrentColdReadsRebuildOnlyOnce() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        clearInvocations(activities);
        List<Integer> statuses = parallel(12, () -> read(fixture.activity, 200).getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        verify(activities, times(1)).selectById(fixture.activity);
    }

    @Test
    void displayUpdateInvalidatesOnlyDisplayAndKeepsRuntimeState() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        read(fixture.activity, 200);
        String key = SessionRuntimeStore.runtimeKey(fixture.session);
        redis.opsForHash().put(key, "available", "6");
        var before = redis.opsForHash().entries(key);
        long expiry = expireAt(key);
        mvc.perform(patch("/api/organizer/activities/" + fixture.activity).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(display("新标题"))).andExpect(status().isOk());
        assertThat(redis.hasKey(ActivityDetailCache.key(fixture.activity))).isFalse();
        JsonNode detail = json.readTree(read(fixture.activity, 200).getResponse().getContentAsByteArray()).path("data");
        assertThat(detail.path("title").asText()).isEqualTo("新标题");
        assertThat(detail.path("coverImage").isNull()).isTrue();
        assertThat(redis.opsForHash().entries(key)).isEqualTo(before);
        assertThat(expireAt(key)).isEqualTo(expiry);
    }

    @Test
    void nearbyFiltersRadiusAndPaginatesActivitiesAtSameLocation() throws Exception {
        Fixture near = fixture(116.397, 39.908);
        publish(near, owner, 200);
        long sameLocation = createActivity(near.location);
        long sameSession = createSession(sameLocation, 10);
        publish(new Fixture(near.location, sameLocation, sameSession), owner, 200);
        Fixture far = fixture(121.47, 31.23);
        publish(far, owner, 200);
        String path = "/api/activities/nearby?longitude=116.397&latitude=39.908&radiusKm=1&size=1";
        var first = request(get(path), student, null, 200).path("data");
        var second = request(get(path + "&page=2"), student, null, 200).path("data");
        assertThat(first.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(first.path("items").get(0).path("distanceKm").asDouble()).isLessThan(0.01);
        assertThat(first.path("items").get(0).path("id")).isNotEqualTo(second.path("items").get(0).path("id"));
        assertThat(request(get("/api/activities/nearby?longitude=0&latitude=0&radiusKm=1"),
                student, null, 200).path("data").path("total").asInt()).isZero();
    }

    @Test
    void concurrentPublicationAndEditNeverLeaveMysqlAndRedisDifferent() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var edit = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(put(sessionPath(fixture)).header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(sessionBody(7))).andReturn().getResponse().getStatus();
            });
            var publish = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return publish(fixture, owner, 200);
            });
            start.countDown();
            assertThat(edit.get(15, TimeUnit.SECONDS)).isIn(200, 409);
            publish.get(15, TimeUnit.SECONDS);
        }
        int capacity = jdbc.queryForObject("SELECT capacity FROM activity_session WHERE id=?", Integer.class, fixture.session);
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(fixture.session), "capacity"))
                .isEqualTo("" + capacity);
    }

    @Test
    void concurrentPublishDoesNotResetOrPartiallyInitializeRuntime() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        parallel(6, () -> publish(fixture, owner, 200));
        assertThat(redis.opsForHash().size(SessionRuntimeStore.runtimeKey(fixture.session))).isEqualTo(10);
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(fixture.session), "available")).isEqualTo("10");
    }

    @Test
    void geoFailureReportsSeparateStatusAndCanBeRetried() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        doThrow(new DataAccessResourceFailureException("simulated GEO failure")).when(geo).index(any());
        JsonNode failed = publish(fixture, owner, 503).path("data");
        assertThat(failed.path("runtimeReady").asBoolean()).isTrue();
        assertThat(failed.path("geoReady").asBoolean()).isFalse();
        doCallRealMethod().when(geo).index(any());
        publish(fixture, owner, 200);
    }

    @Test
    void expiredDraftCannotPublishAndDatabaseConstraintsRejectInvalidCapacity() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        jdbc.update("UPDATE activity_session SET registration_start_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), fixture.session);
        publish(fixture, owner, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM activity_session WHERE id=?", String.class, fixture.session))
                .isEqualTo("DRAFT");
        assertThat(redis.hasKey(SessionRuntimeStore.runtimeKey(fixture.session))).isFalse();
        assertThatThrownBy(() -> jdbc.update("UPDATE activity_session SET capacity=0 WHERE id=?", fixture.session))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().extracting(exception -> ((java.sql.SQLException) exception).getErrorCode()).isEqualTo(3819);
        assertThat(jdbc.queryForObject("SELECT capacity FROM activity_session WHERE id=?", Integer.class, fixture.session))
                .isEqualTo(10);
    }

    @Test
    void lockTimeoutDoesNotFallBackToUnprotectedDatabaseLoad() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        clearInvocations(activities);
        var lock = redisson.getLock(ActivityDetailCache.lockKey(fixture.activity));
        lock.lock();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> read(fixture.activity, 503).getResponse().getStatus());
            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(503);
            verify(activities, never()).selectById(fixture.activity);
        } finally { lock.unlock(); }
    }

    @Test
    void slowCacheRebuildCannotWriteOldContentAfterDisplayUpdate() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        publish(fixture, owner, 200);
        var old = json.treeToValue(json.readTree(read(fixture.activity, 200).getResponse().getContentAsByteArray())
                .path("data"), com.campusbooking.activity.dto.ActivityViews.Detail.class);
        redis.delete(ActivityDetailCache.key(fixture.activity));
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finishLoading = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reader = pool.submit(() -> cache.get(fixture.activity, () -> {
                loading.countDown();
                try {
                    if (!finishLoading.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("loader timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return old;
            }));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> request(patch("/api/organizer/activities/" + fixture.activity),
                    owner, display("并发更新"), 200));
            finishLoading.countDown();
            reader.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(json.readTree(read(fixture.activity, 200).getResponse().getContentAsByteArray())
                .path("data").path("title").asText()).isEqualTo("并发更新");
    }

    @Test
    void combinedSynchronizationFailureStillReportsCommittedPublication() throws Exception {
        Fixture fixture = fixture(116.397, 39.908);
        doThrow(new DataAccessResourceFailureException("runtime unavailable")).when(runtime).initialize(any());
        doThrow(new DataAccessResourceFailureException("cache unavailable")).when(cache).invalidate(fixture.activity);
        JsonNode failed = publish(fixture, owner, 503);
        assertThat(failed.path("message").asText()).contains("配置已保存", "暂不可报名", "展示缓存失效未完成");
        assertThat(failed.path("data").path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(failed.path("data").path("displayReady").asBoolean()).isFalse();
    }

    @Test
    void luaRejectsInvalidArgumentsBeforeAnyWrite() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("redis/initialize-session.lua"));
        script.setResultType(Long.class);
        List<String> keys = List.of("registration:session:bad:runtime", "registration:session:bad:users");
        Long result = redis.execute(script, keys, "1", "2", "not-a-number", "100", "200", "300", "400", "500");
        assertThat(result).isEqualTo(-1L);
        assertThat(redis.hasKey(keys.getFirst())).isFalse();
        assertThat(redis.hasKey(keys.getLast())).isFalse();
    }

    private String identity(String phone, Role role) {
        jdbc.update("INSERT INTO account_user(phone,nickname,role) VALUES (?,?,?)", phone, role.name(), role.name());
        Long id = jdbc.queryForObject("SELECT id FROM account_user WHERE phone=?", Long.class, phone);
        return "Bearer " + accounts.createToken(new UserIdentity(id, role));
    }

    private Fixture fixture(double longitude, double latitude) throws Exception {
        long location = request(post("/api/organizer/locations"), owner, location(longitude, latitude), 200)
                .path("data").path("id").asLong();
        long activity = createActivity(location);
        return new Fixture(location, activity, createSession(activity, 10));
    }

    private long createActivity(long locationId) throws Exception {
        return request(post("/api/organizer/activities"), owner,
                json.writeValueAsString(Map.of("locationId", locationId, "title", "校园读书会",
                        "description", "一起读书", "coverImage", "https://example.com/cover.jpg")), 200)
                .path("data").path("id").asLong();
    }

    private long createSession(long activityId, int capacity) throws Exception {
        return request(post("/api/organizer/activities/" + activityId + "/sessions"), owner, sessionBody(capacity), 200)
                .path("data").path("id").asLong();
    }

    private String sessionBody(int capacity) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return json.writeValueAsString(Map.of("capacity", capacity, "registrationStartAt", now.plusSeconds(3600),
                "registrationEndAt", now.plusSeconds(7200), "startAt", now.plusSeconds(10800),
                "endAt", now.plusSeconds(14400)));
    }

    private String location(double longitude, double latitude) throws Exception {
        return json.writeValueAsString(Map.of("name", "活动中心", "address", "校园内",
                "longitude", longitude, "latitude", latitude));
    }

    private String display(String title) throws Exception {
        return json.writeValueAsString(Map.of("title", title, "description", "更新后的介绍"));
    }

    private JsonNode publish(Fixture fixture, String token, int status) throws Exception {
        return request(post(sessionPath(fixture) + "/publish"), token, null, status);
    }

    private String sessionPath(Fixture fixture) {
        return "/api/organizer/activities/" + fixture.activity + "/sessions/" + fixture.session;
    }

    private JsonNode request(MockHttpServletRequestBuilder builder, String token, String body, int status) throws Exception {
        builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return json.readTree(mvc.perform(builder).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());
    }

    private MvcResult read(long id, int status) throws Exception {
        return mvc.perform(get("/api/activities/" + id).header("Authorization", student))
                .andExpect(status().is(status)).andReturn();
    }

    private long expireAt(String key) {
        return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
    }

    private <T> List<T> parallel(int count, Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                T value = action.call();
                assertThat(UserContext.current()).isNull();
                return value;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) values.add(future.get(20, TimeUnit.SECONDS));
            return values;
        }
    }

    private record Fixture(long location, long activity, long session) {}
}
