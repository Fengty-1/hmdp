package com.campusbooking.participation;

import com.campusbooking.account.auth.*;
import com.campusbooking.account.model.Role;
import com.campusbooking.common.ApiException;
import com.campusbooking.participation.mapper.ParticipationMapper;
import com.campusbooking.registration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
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
class ParticipationFlowIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("campus_participation_test").withUsername("test").withPassword("test");
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

    @Autowired ParticipationService service;
    @Autowired RegistrationConsumerService consumer;
    @Autowired AccountRedisStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean ParticipationMapper mapper;
    Instant now;
    long registrationId;
    String studentToken;
    String organizerToken;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM participation_record");
        jdbc.update("DELETE FROM participation_credential");
        jdbc.update("DELETE FROM registration_result");
        jdbc.update("DELETE FROM registration");
        jdbc.update("DELETE FROM activity_session");
        jdbc.update("DELETE FROM activity");
        jdbc.update("DELETE FROM activity_location");
        jdbc.update("DELETE FROM account_user");
        jdbc.update("INSERT INTO account_user(id,phone,nickname,role) VALUES "
                + "(1,'13900000001','组织者','ORGANIZER'),(2,'13900000002','其他组织者','ORGANIZER'),"
                + "(3,'13800000003','学生','STUDENT'),(4,'13800000004','其他学生','STUDENT')");
        jdbc.update("INSERT INTO activity_location(id,organizer_id,name,address,longitude,latitude) VALUES(1,1,'校园','校园',116,39)");
        jdbc.update("INSERT INTO activity(id,organizer_id,location_id,title,description) VALUES(1,1,1,'活动','描述')");
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        doReturn(now).when(clock).instant();
        jdbc.update("INSERT INTO activity_session(id,activity_id,capacity,remaining_capacity,registration_start_at,registration_end_at,start_at,end_at,status,runtime_expire_at) "
                + "VALUES(1,1,5,5,?,?,?,?,'PUBLISHED',?)", ts(now.minusSeconds(300)), ts(now.minusSeconds(100)),
                ts(now.minusSeconds(60)), ts(now.plusSeconds(3600)), ts(now.plusSeconds(90000)));
        // 正式报名通过 Stage 3 的真实事务创建，凭证不接受只有 PENDING 的请求。
        registrationId = consumer.consume(new RegistrationMessage("10001", 3, 1, now.minusSeconds(150).toEpochMilli())).registrationId();
        studentToken = token(3, Role.STUDENT);
        organizerToken = token(1, Role.ORGANIZER);
        as(3, Role.STUDENT);
    }
    @AfterEach void clearContext() { UserContext.clear(); }

    @Test
    void realHttpStartupCredentialVerificationAndLists() throws Exception {
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        HttpHeaders student = new HttpHeaders();
        student.set("Authorization", studentToken);
        var issued = http.exchange("/api/registrations/" + registrationId + "/credential", HttpMethod.POST,
                new HttpEntity<>(student), String.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = json.readTree(issued.getBody()).path("data").path("code").asText();
        HttpHeaders organizer = new HttpHeaders();
        organizer.set("Authorization", organizerToken);
        var verified = http.exchange("/api/sessions/1/participations/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code), organizer), String.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(verified.getBody()).path("data").path("registrationId").asLong()).isEqualTo(registrationId);
        mvc.perform(get("/api/participations/me").header("Authorization", studentToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/sessions/1/participations").header("Authorization", organizerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(3))
                .andExpect(jsonPath("$.data.items[0].code").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT status FROM registration_result WHERE request_id='10001'", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT remaining_capacity FROM activity_session WHERE id=1", Integer.class)).isEqualTo(4);
    }

    @Test
    void concurrentCredentialIssuanceReturnsOneStableCode() throws Exception {
        var credentials = parallel(12, 3, Role.STUDENT, () -> service.issue(registrationId));
        assertThat(credentials.stream().map(ParticipationViews.Credential::code).distinct()).hasSize(1);
        assertThat(credentials.getFirst().code()).matches("[0-9a-f]{32}");
        assertThat(count("participation_credential")).isEqualTo(1);
    }

    @Test
    void concurrentVerificationRecordsOneFactAndKeepsFirstTimestamp() throws Exception {
        String code = service.issue(registrationId).code();
        var records = parallel(12, 1, Role.ORGANIZER, () -> service.verify(1, code));
        assertThat(records.stream().distinct()).hasSize(1);
        assertThat(count("participation_record")).isEqualTo(1);
        as(1, Role.ORGANIZER);
        doReturn(now.plusSeconds(30)).when(clock).instant();
        assertThat(service.verify(1, code).checkedAt()).isEqualTo(now);
    }

    @Test
    void credentialRequiresOwnedSuccess() {
        as(4, Role.STUDENT);
        rejects(() -> service.issue(registrationId), HttpStatus.NOT_FOUND);
        as(1, Role.ORGANIZER);
        rejects(() -> service.issue(registrationId), HttpStatus.FORBIDDEN);
        as(3, Role.STUDENT);
        rejects(() -> service.issue(99999), HttpStatus.NOT_FOUND);
        jdbc.update("DELETE FROM registration_result");
        rejects(() -> service.issue(registrationId), HttpStatus.NOT_FOUND);
        assertThat(count("participation_credential")).isZero();
    }

    @Test
    void pendingAndFailedCannotGetCredentials() {
        jdbc.update("INSERT INTO registration_result(request_id,user_id,session_id,status,failure_code,completed_at) "
                + "VALUES('99999',4,1,'FAILED','CAPACITY_EXHAUSTED',?)", ts(now));
        as(4, Role.STUDENT);
        rejects(() -> service.issue(99999), HttpStatus.NOT_FOUND);
        rejects(() -> service.issue(88888), HttpStatus.NOT_FOUND);
        assertThat(count("participation_credential")).isZero();
    }

    @Test
    void roleOwnershipAndWrongSessionAreRejected() {
        String code = service.issue(registrationId).code();
        rejects(() -> service.verify(1, code), HttpStatus.FORBIDDEN);
        as(2, Role.ORGANIZER);
        rejects(() -> service.verify(1, code), HttpStatus.FORBIDDEN);
        rejects(() -> service.attendees(1, 1, 10), HttpStatus.FORBIDDEN);
        as(1, Role.ORGANIZER);
        jdbc.update("INSERT INTO activity_session(id,activity_id,capacity,remaining_capacity,registration_start_at,registration_end_at,start_at,end_at,status,runtime_expire_at) "
                + "SELECT 2,activity_id,capacity,remaining_capacity,registration_start_at,registration_end_at,start_at,end_at,status,runtime_expire_at FROM activity_session WHERE id=1");
        rejects(() -> service.verify(2, code), HttpStatus.NOT_FOUND);
        rejects(() -> service.verify(1, "0".repeat(32)), HttpStatus.NOT_FOUND);
        assertThat(count("participation_record")).isZero();
    }

    @Test
    void timeWindowIncludesStartAndExcludesEndEvenForRepeat() {
        String code = service.issue(registrationId).code();
        as(1, Role.ORGANIZER);
        doReturn(now.minusSeconds(60).minusMillis(1)).when(clock).instant();
        rejects(() -> service.verify(1, code), HttpStatus.CONFLICT);
        doReturn(now.minusSeconds(60)).when(clock).instant();
        assertThat(service.verify(1, code).checkedAt()).isEqualTo(now.minusSeconds(60));
        doReturn(now.plusSeconds(3600)).when(clock).instant();
        rejects(() -> service.verify(1, code), HttpStatus.CONFLICT);
        assertThat(count("participation_record")).isEqualTo(1);
    }

    @Test
    void endTimeRejectsFirstVerification() {
        String code = service.issue(registrationId).code();
        as(1, Role.ORGANIZER);
        doReturn(now.plusSeconds(3600)).when(clock).instant();
        rejects(() -> service.verify(1, code), HttpStatus.CONFLICT);
        assertThat(count("participation_record")).isZero();
    }

    @Test
    void lostSuccessFactPreventsVerification() {
        String code = service.issue(registrationId).code();
        jdbc.update("DELETE FROM registration_result");
        as(1, Role.ORGANIZER);
        rejects(() -> service.verify(1, code), HttpStatus.NOT_FOUND);
        assertThat(count("participation_record")).isZero();
    }

    @Test
    void exceptionAfterInsertRollsBackAndAllowsRetry() {
        String code = service.issue(registrationId).code();
        as(1, Role.ORGANIZER);
        doReturn(null).doAnswer(invocation -> {
            assertThat(count("participation_record")).isEqualTo(1);
            throw new DataAccessResourceFailureException("test rollback after insert");
        }).when(mapper).attendance(registrationId);
        assertThatThrownBy(() -> service.verify(1, code)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("participation_record")).isZero();
        reset(mapper);
        assertThat(service.verify(1, code).registrationId()).isEqualTo(registrationId);
    }

    @Test
    void databaseConstraintsRejectDuplicatesAndOrphans() {
        String code = service.issue(registrationId).code();
        as(1, Role.ORGANIZER);
        service.verify(1, code);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO participation_record VALUES(?,1,?)", registrationId, ts(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO participation_record VALUES(99999,1,?)", ts(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO participation_credential VALUES(99999,?)", "a".repeat(32)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listsAreScopedPaginatedAndDoNotExposeCredentials() {
        String code = service.issue(registrationId).code();
        as(1, Role.ORGANIZER);
        assertThat(service.attendees(1, 1, 10).total()).isZero();
        service.verify(1, code);
        assertThat(service.attendees(1, 2, 1).items()).isEmpty();
        as(4, Role.STUDENT);
        assertThat(service.mine(1, 10).total()).isZero();
        as(3, Role.STUDENT);
        assertThat(service.mine(1, 1).items()).hasSize(1);
        rejects(() -> service.mine(0, 10), HttpStatus.BAD_REQUEST);
        rejects(() -> service.mine(1, 51), HttpStatus.BAD_REQUEST);
    }

    @Test
    void httpAuthenticationAndInputValidation() throws Exception {
        mvc.perform(post("/api/registrations/" + registrationId + "/credential")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/participations/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sessions/1/participations").header("Authorization", studentToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/1/participations/verify").header("Authorization", organizerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"bad\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/registrations/0/credential").header("Authorization", studentToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/participations/me?size=51").header("Authorization", studentToken)).andExpect(status().isBadRequest());
    }

    private String token(long id, Role role) { return "Bearer " + accounts.createToken(new UserIdentity(id, role)); }
    private void as(long id, Role role) { UserContext.set(new UserIdentity(id, role)); }
    private Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void rejects(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(status));
    }
    private <T> List<T> parallel(int count, long id, Role role, Supplier<T> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(count)) {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                as(id, role);
                try { return action.get(); } finally { UserContext.clear(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
