package com.campusbooking.registration;

import com.campusbooking.account.auth.*;
import com.campusbooking.account.model.Role;
import com.campusbooking.activity.SessionRuntimeStore;
import com.campusbooking.activity.mapper.SessionMapper;
import com.campusbooking.config.RegistrationConfig;
import com.campusbooking.registration.mapper.RegistrationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class RegistrationFlowIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("campus_registration_test").withUsername("test").withPassword("test");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.8"))
            .withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.2.5-management"));
    @DynamicPropertySource
    static void services(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired AccountRedisStore accounts;
    @Autowired SessionRuntimeStore runtime;
    @Autowired SessionMapper sessions;
    @Autowired RegistrationRedisStore reservations;
    @Autowired RequestIdGenerator ids;
    @Autowired RegistrationConsumerService consumer;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired TestRestTemplate http;
    @MockitoSpyBean RegistrationPublisher publisher;
    @MockitoSpyBean RegistrationMapper registrations;
    @MockitoSpyBean Clock clock;
    RabbitAdmin admin;
    long sessionId;
    long userId;
    String token;

    @BeforeEach
    void isolatedFixture() {
        admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.initialize();
        admin.purgeQueue(RegistrationConfig.QUEUE, false);
        admin.purgeQueue(RegistrationConfig.DEAD_QUEUE, false);
        jdbc.update("DELETE FROM registration_result");
        jdbc.update("DELETE FROM registration");
        jdbc.update("DELETE FROM activity_session");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM activity_location");
        jdbc.update("DELETE FROM account_user");
        redis.execute((RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; });
        jdbc.update("INSERT INTO account_user(phone,nickname,role) VALUES('13900000001','组织者','ORGANIZER')");
        long owner = jdbc.queryForObject("SELECT id FROM account_user", Long.class);
        jdbc.update("INSERT INTO activity_location(organizer_id,name,address,longitude,latitude) VALUES(?,'校园','校园',116,39)", owner);
        long location = jdbc.queryForObject("SELECT id FROM activity_location", Long.class);
        jdbc.update("INSERT INTO activity(organizer_id,location_id,title,description) VALUES(?,?,'测试活动','描述')", owner, location);
        long activity = jdbc.queryForObject("SELECT id FROM activity", Long.class);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO activity_session(activity_id,capacity,remaining_capacity,registration_start_at,registration_end_at,start_at,end_at,status,runtime_expire_at) "
                        + "VALUES(?,5,5,?,?,?,?, 'PUBLISHED',?)", activity,
                java.sql.Timestamp.from(now.plusSeconds(60)), java.sql.Timestamp.from(now.plusSeconds(600)),
                java.sql.Timestamp.from(now.plusSeconds(700)), java.sql.Timestamp.from(now.plusSeconds(3600)),
                java.sql.Timestamp.from(now.plusSeconds(3600 + 86400)));
        sessionId = jdbc.queryForObject("SELECT id FROM activity_session", Long.class);
        assertThat(runtime.initialize(sessions.selectById(sessionId))).isTrue();
        // 仅测试夹具推进报名窗口，生产 API 禁止修改已发布的核心配置。
        jdbc.update("UPDATE activity_session SET registration_start_at=? WHERE id=?", java.sql.Timestamp.from(now.minusSeconds(60)), sessionId);
        redis.opsForHash().put(SessionRuntimeStore.runtimeKey(sessionId), "registrationStartAt", Long.toString(now.minusSeconds(60).toEpochMilli()));
        userId = student(0);
        token = "Bearer " + accounts.createToken(new UserIdentity(userId, Role.STUDENT));
    }

    @AfterEach
    void stopConsumer() {
        listeners.getListenerContainer("registrationConsumer").stop();
        UserContext.clear();
    }

    @Test
    void realHttpStartupAndAsyncRoundTrip() throws Exception {
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", token);
        var accepted = http.exchange("/api/sessions/" + sessionId + "/registrations",
                org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<>(headers), String.class);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        var body = json.readTree(accepted.getBody()).path("data");
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("requestId").isTextual()).isTrue();
        String id = body.path("requestId").asText();
        assertThat(registrations.result(id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration", Integer.class)).isZero();
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        listeners.getListenerContainer("registrationConsumer").start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(registrations.result(id)).isNotNull();
            assertThat(registrations.result(id).status()).isEqualTo("SUCCESS");
        });
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mvc.perform(get("/api/registrations/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        assertThat(reservations.pending(id)).containsEntry("status", "PENDING");
        assertThat(remaining()).isEqualTo(4);
    }

    @Test
    void concurrentDifferentUsersNeverExceedCapacityAndAllAcceptedFinish() throws Exception {
        List<Long> users = new ArrayList<>();
        for (int i = 1; i <= 30; i++) users.add(student(i));
        List<String> decisions = parallel(30, i -> reservations.accept(sessionId, users.get(i), ids.next()));
        List<RegistrationMessage> accepted = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            String decision = decisions.get(i);
            if (decision.startsWith("ACCEPTED:")) {
                String[] parts = decision.split(":");
                accepted.add(new RegistrationMessage(parts[1], users.get(i), sessionId, Long.parseLong(parts[2])));
            } else assertThat(decision).isEqualTo("FULL");
        }
        assertThat(accepted).hasSize(5);
        assertThat(redis.keys("registration:request:*")).hasSize(5);
        accepted.forEach(publisher::send);
        listeners.getListenerContainer("registrationConsumer").start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration", Integer.class)).isEqualTo(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration_result WHERE status='SUCCESS'", Integer.class)).isEqualTo(5);
        assertThat(remaining()).isZero();
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "available")).isEqualTo("0");
    }

    @Test
    void twentyDuplicateHttpRequestsReturnOneIdAndPublishOnce() throws Exception {
        List<String> results = parallel(20, i -> {
            try { return submit(token); } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThat(new HashSet<>(results)).hasSize(1);
        verify(publisher, times(1)).send(any());
        assertThat(redis.keys("registration:request:*")).hasSize(1);
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "available")).isEqualTo("4");
    }

    @Test
    void duplicateConsumptionAndUserSessionConstraintDoNotTakeExtraCapacity() throws Exception {
        RegistrationMessage message = accept();
        var results = parallel(12, i -> consumer.consume(message));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo("SUCCESS"));
        assertThat(remaining()).isEqualTo(4);
        var duplicateUser = consumer.consume(new RegistrationMessage(ids.next(), userId, sessionId, message.acceptedAt()));
        assertThat(duplicateUser.failureCode()).isEqualTo("ALREADY_REGISTERED");
        assertThat(remaining()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO registration(request_id,user_id,session_id) VALUES(?,?,?)", ids.next(), userId, sessionId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void rollbackAfterResultWriteFailureLeavesNoPartialBusinessData() {
        RegistrationMessage message = accept();
        doThrow(new DataAccessResourceFailureException("simulated result storage outage")).when(registrations).insertResult(any());
        assertThatThrownBy(() -> consumer.consume(message)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(remaining()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration", Integer.class)).isZero();
        assertThat(registrations.result(message.requestId())).isNull();
        assertThat(reservations.pending(message.requestId())).containsEntry("status", "PENDING");
    }

    @Test
    void sendExceptionKeepsAcceptedResponseAndDoesNotRepublishOnRetry() throws Exception {
        doThrow(new IllegalStateException("simulated send failure")).when(publisher).send(any());
        String id = submit(token);
        assertThat(submit(token)).isEqualTo(id);
        verify(publisher, times(1)).send(any());
        assertThat(registrations.result(id)).isNull();
        assertThat(reservations.pending(id)).containsEntry("status", "PENDING");
        assertThat(remaining()).isEqualTo(5);
    }

    @Test
    void interruptionBetweenLuaAndSendLeavesPendingWithoutAutomaticRecovery() {
        RegistrationMessage message = accept();
        assertThat(registrations.result(message.requestId())).isNull();
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("EXISTING:" + message.requestId());
        verifyNoInteractions(publisher);
        assertThat(remaining()).isEqualTo(5);
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "available")).isEqualTo("4");
    }

    @Test
    void absoluteExpiryAndPollingNeverExtendLifetime() throws Exception {
        String id = submit(token);
        long expires = Long.parseLong((String) redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "expireAt"));
        assertThat(expiry(SessionRuntimeStore.runtimeKey(sessionId))).isEqualTo(expires);
        assertThat(expiry(SessionRuntimeStore.usersKey(sessionId))).isEqualTo(expires);
        assertThat(expiry(RegistrationRedisStore.requestKey(id))).isEqualTo(expires);
        submit(token);
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", token)).andExpect(status().isOk());
        assertThat(expiry(RegistrationRedisStore.requestKey(id))).isEqualTo(expires);
        assertThat(expiry(SessionRuntimeStore.usersKey(sessionId))).isEqualTo(expires);
    }

    @Test
    void lateMessagesFailButNeverRewriteCommittedSuccess() {
        RegistrationMessage success = accept();
        consumer.consume(success);
        long second = student(2);
        String[] decision = reservations.accept(sessionId, second, ids.next()).split(":");
        RegistrationMessage late = new RegistrationMessage(decision[1], second, sessionId, Long.parseLong(decision[2]));
        doReturn(sessions.selectById(sessionId).getEndAt().plusSeconds(1)).when(clock).instant();
        assertThat(consumer.consume(success).status()).isEqualTo("SUCCESS");
        assertThat(consumer.consume(late).failureCode()).isEqualTo("SESSION_ENDED");
        assertThat(remaining()).isEqualTo(4);
    }

    @Test
    void capacityFailureIsTerminalWithoutPartialRegistration() {
        RegistrationMessage message = accept();
        jdbc.update("UPDATE activity_session SET remaining_capacity=0 WHERE id=?", sessionId);
        var result = consumer.consume(message);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo("CAPACITY_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration", Integer.class)).isZero();
        assertThat(remaining()).isZero();
    }

    @Test
    void missingRuntimeOrInvalidTypesNeverWriteReservations() {
        String key = SessionRuntimeStore.runtimeKey(sessionId);
        redis.delete(key);
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("RUNTIME_NOT_READY");
        redis.opsForValue().set(key, "wrong-type");
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("RUNTIME_INVALID");
        assertThat(redis.keys("registration:request:*")).isEmpty();
        assertThat(redis.hasKey(SessionRuntimeStore.usersKey(sessionId))).isFalse();
    }

    @Test
    void corruptUsersOrCandidateKeyAndInvalidArgumentsAreRejectedBeforeDeduction() {
        String users = SessionRuntimeStore.usersKey(sessionId);
        redis.opsForValue().set(users, "wrong-type");
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("RUNTIME_INVALID");
        redis.delete(users);
        String id = ids.next();
        redis.opsForValue().set(RegistrationRedisStore.requestKey(id), "collision");
        assertThat(reservations.accept(sessionId, userId, id)).isEqualTo("RUNTIME_INVALID");
        assertThat(reservations.accept(sessionId, userId, "bad-id")).isEqualTo("INVALID_ARGUMENT");
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "available")).isEqualTo("5");
        assertThat(redis.hasKey(users)).isFalse();
    }

    @Test
    void registrationWindowAndExistingRequestAfterClose() {
        String key = SessionRuntimeStore.runtimeKey(sessionId);
        redis.opsForHash().put(key, "registrationStartAt", Long.toString(Instant.now().plusSeconds(60).toEpochMilli()));
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("NOT_OPEN");
        redis.opsForHash().put(key, "registrationStartAt", Long.toString(Instant.now().minusSeconds(60).toEpochMilli()));
        RegistrationMessage accepted = accept();
        redis.opsForHash().put(key, "registrationEndAt", Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));
        assertThat(reservations.accept(sessionId, userId, ids.next())).isEqualTo("EXISTING:" + accepted.requestId());
        assertThat(reservations.accept(sessionId, student(3), ids.next())).isEqualTo("CLOSED");
    }

    @Test
    void ownershipUnknownResultsAndDatabaseFailureRemainDistinct() throws Exception {
        String id = submit(token);
        String other = "Bearer " + accounts.createToken(new UserIdentity(student(4), Role.STUDENT));
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        consumer.consume(message(id));
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/registrations/results/123").header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESULT_NOT_FOUND"));
        doThrow(new DataAccessResourceFailureException("simulated query failure")).when(registrations).result(id);
        mvc.perform(get("/api/registrations/results/" + id).header("Authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RESULT_UNAVAILABLE"));
    }

    @Test
    void expiredPendingIsUnknownAndDoesNotMeanFailure() throws Exception {
        RegistrationMessage message = accept();
        redis.delete(RegistrationRedisStore.requestKey(message.requestId()));
        mvc.perform(get("/api/registrations/results/" + message.requestId()).header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESULT_NOT_FOUND"));
        assertThat(registrations.result(message.requestId())).isNull();
        assertThat(redis.opsForHash().get(SessionRuntimeStore.runtimeKey(sessionId), "available")).isEqualTo("4");
    }

    @Test
    void permissionsAndParameters() throws Exception {
        mvc.perform(post("/api/sessions/" + sessionId + "/registrations")).andExpect(status().isUnauthorized());
        long owner = jdbc.queryForObject("SELECT id FROM account_user WHERE role='ORGANIZER'", Long.class);
        String organizer = "Bearer " + accounts.createToken(new UserIdentity(owner, Role.ORGANIZER));
        mvc.perform(post("/api/sessions/" + sessionId + "/registrations").header("Authorization", organizer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/0/registrations").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/registrations/results/9999999999999999999").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/registrations/me?size=51").header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test
    void consumerTechnicalFailureGoesToDeadLetterWithoutFailedResult() throws Exception {
        doThrow(new DataAccessResourceFailureException("simulated transaction failure")).when(registrations).insertResult(any());
        String id = submit(token);
        listeners.getListenerContainer("registrationConsumer").start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(admin.getQueueInfo(RegistrationConfig.DEAD_QUEUE).getMessageCount()).isEqualTo(1));
        assertThat(registrations.result(id)).isNull();
        assertThat(remaining()).isEqualTo(5);
        assertThat(reservations.pending(id)).containsEntry("status", "PENDING");
        var dead = rabbit.receive(RegistrationConfig.DEAD_QUEUE);
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(dead.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void brokerDuplicateDeliveriesAllAckWithoutDoubleDeduction() {
        RegistrationMessage message = accept();
        for (int i = 0; i < 8; i++) publisher.send(message);
        listeners.getListenerContainer("registrationConsumer").start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(registrations.result(message.requestId())).isNotNull();
            verify(registrations, times(1)).insertRegistration(any());
            verify(registrations, atLeast(8)).result(message.requestId());
        });
        listeners.getListenerContainer("registrationConsumer").stop();
        assertThat(remaining()).isEqualTo(4);
        assertThat(admin.getQueueInfo(RegistrationConfig.QUEUE).getMessageCount()).isZero();
        assertThat(admin.getQueueInfo(RegistrationConfig.DEAD_QUEUE).getMessageCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM registration_result", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseConstraintsProtectRequestIdAndTerminalStates() {
        RegistrationMessage message = accept();
        consumer.consume(message);
        long other = student(10);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO registration(request_id,user_id,session_id) VALUES(?,?,?)",
                message.requestId(), other, sessionId)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO registration_result(request_id,user_id,session_id,status,completed_at) VALUES(?,?,?,'PENDING',NOW(3))",
                ids.next(), other, sessionId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void idGenerationRetainsSequenceAcrossClockRollback() {
        Instant now = Instant.now();
        doReturn(now).when(clock).instant();
        String first = ids.next();
        doReturn(now.minusSeconds(5)).when(clock).instant();
        String backwards = ids.next();
        doReturn(now).when(clock).instant();
        String second = ids.next();
        assertThat(Set.of(first, backwards, second)).hasSize(3);
        String counter = "registration:id:" + now.atOffset(ZoneOffset.UTC).toLocalDate();
        assertThat(redis.getExpire(counter)).isEqualTo(-1);
    }

    @Test
    void realBrokerUnroutablePublishPreservesPendingAndLogsRequestId(CapturedOutput output) throws Exception {
        var binding = new org.springframework.amqp.core.Binding(RegistrationConfig.QUEUE,
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                RegistrationConfig.EXCHANGE, RegistrationConfig.ROUTING_KEY, null);
        admin.removeBinding(binding);
        try {
            String id = submit(token);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(output).contains("publish unconfirmed requestId=" + id + " returned=true"));
            assertThat(registrations.result(id)).isNull();
            assertThat(reservations.pending(id)).containsEntry("status", "PENDING");
            assertThat(remaining()).isEqualTo(5);
            assertThat(admin.getQueueInfo(RegistrationConfig.QUEUE).getMessageCount()).isZero();
        } finally {
            admin.declareBinding(binding);
        }
    }

    private RegistrationMessage accept() {
        String[] parts = reservations.accept(sessionId, userId, ids.next()).split(":");
        assertThat(parts[0]).isEqualTo("ACCEPTED");
        return new RegistrationMessage(parts[1], userId, sessionId, Long.parseLong(parts[2]));
    }
    private RegistrationMessage message(String id) {
        var pending = reservations.pending(id);
        return new RegistrationMessage(id, Long.parseLong((String) pending.get("userId")), sessionId,
                Long.parseLong((String) pending.get("acceptedAt")));
    }
    private String submit(String auth) throws Exception {
        String body = mvc.perform(post("/api/sessions/" + sessionId + "/registrations").header("Authorization", auth))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        var id = json.readTree(body).path("data").path("requestId");
        assertThat(id.isTextual()).isTrue();
        return id.asText();
    }
    private long student(int n) {
        String phone = "138" + String.format("%08d", n);
        jdbc.update("INSERT INTO account_user(phone,nickname,role) VALUES(?,'学生','STUDENT')", phone);
        return jdbc.queryForObject("SELECT id FROM account_user WHERE phone=?", Long.class, phone);
    }
    private int remaining() { return jdbc.queryForObject("SELECT remaining_capacity FROM activity_session WHERE id=?", Integer.class, sessionId); }
    private long expiry(String key) { return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key)); }
    private <T> List<T> parallel(int count, IntFunction<T> task) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { start.await(); return task.apply(index); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
