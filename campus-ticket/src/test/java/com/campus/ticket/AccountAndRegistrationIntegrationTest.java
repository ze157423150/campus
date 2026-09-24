package com.campus.ticket;

import com.campus.ticket.constants.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: uses configured MySQL/Redis; creates and removes only its own fixtures. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountAndRegistrationIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper json;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> users = new ArrayList<>();
    final List<Long> activities = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    static final String PASSWORD = "IntegrationTest123!";

    record Result(int status, JsonNode body) { }
    record Account(long id, String studentNo) { }

    Result request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body().isBlank()
                ? json.readTree("null") : json.readTree(response.body()));
    }

    JsonNode expect(int status, Result result) {
        assertEquals(status, result.status(), () -> result.body().toString());
        return result.body();
    }

    String login(Account account, String password) throws Exception {
        JsonNode result = expect(200, request("POST", "/auth/login", null,
                Map.of("studentNo", account.studentNo(), "password", password)));
        String token = result.path("token").asText();
        assertTrue(token.matches("[0-9a-f]{32}"));
        tokens.add(token);
        return token;
    }

    Account fixtureUser(String role) {
        String studentNo = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("INSERT INTO campus_user (student_no, name, password_hash, role) VALUES (?, ?, ?, ?)",
                studentNo, "集成测试用户", encoder.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM campus_user WHERE student_no = ?", Long.class, studentNo);
        users.add(id);
        return new Account(id, studentNo);
    }

    long fixtureActivity() {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO activity (title, location, start_time, end_time,
                        registration_start_time, registration_end_time, total_quota, remaining_quota, status)
                    VALUES (?, '测试教室', NOW()+INTERVAL 7 DAY, NOW()+INTERVAL 8 DAY,
                        NOW()-INTERVAL 1 DAY, NOW()+INTERVAL 6 DAY, 10, 10, 'PUBLISHED')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "自动测试-" + UUID.randomUUID());
            return statement;
        }, key);
        long id = key.getKey().longValue();
        activities.add(id);
        return id;
    }

    @AfterEach
    void cleanup() {
        // Redis failure must not prevent removal of this test's MySQL fixtures.
        try {
            for (String token : tokens) redis.delete(RedisConstants.LOGIN_KEY_PREFIX + token);
            for (Long id : activities) redis.delete(RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + id);
        } finally {
            for (Long id : activities) {
                jdbc.update("DELETE FROM registration WHERE activity_id = ?", id);
                jdbc.update("DELETE FROM activity WHERE id = ?", id);
            }
            for (Long id : users) jdbc.update("DELETE FROM campus_user WHERE id = ?", id);
        }
    }

    void assertQuotaInvariant(long activityId, int expectedRemaining, int expectedRegistered) {
        assertEquals(expectedRemaining, jdbc.queryForObject(
                "SELECT remaining_quota FROM activity WHERE id=?", Integer.class, activityId));
        assertEquals(expectedRegistered, jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration WHERE activity_id=? AND status='REGISTERED'", Integer.class, activityId));
        int total = jdbc.queryForObject("SELECT total_quota FROM activity WHERE id=?", Integer.class, activityId);
        assertEquals(total, expectedRemaining + expectedRegistered);
    }

    List<Result> concurrentRegistrations(long activityId, List<String> requestTokens) throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(requestTokens.size());
        var ready = new java.util.concurrent.CountDownLatch(requestTokens.size());
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Result>>();
        try {
            for (String token : requestTokens) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return request("POST", "/activities/" + activityId + "/registrations", token, null);
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<Result>();
            for (var future : futures) results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentQuotaAndDuplicateRegistration() throws Exception {
        var students = new ArrayList<String>();
        for (int i = 0; i < 6; i++) students.add(login(fixtureUser("STUDENT"), PASSWORD));
        long limited = fixtureActivity();
        jdbc.update("UPDATE activity SET total_quota=2, remaining_quota=2 WHERE id=?", limited);
        var results = concurrentRegistrations(limited, students);
        assertEquals(2, results.stream().filter(r -> r.status() == 200).count());
        for (Result result : results) {
            if (result.status() != 200) assertEquals("QUOTA_EXHAUSTED", expect(409, result).path("code").asText());
        }
        assertQuotaInvariant(limited, 0, 2);
        long sameUser = fixtureActivity();
        var duplicates = concurrentRegistrations(sameUser, java.util.Collections.nCopies(6, students.get(0)));
        assertEquals(1, duplicates.stream().filter(r -> r.status() == 200).count());
        for (Result result : duplicates) {
            if (result.status() != 200) assertEquals("DUPLICATE_REGISTRATION", expect(409, result).path("code").asText());
        }
        assertQuotaInvariant(sameUser, 9, 1);
    }

    @Test
    void cancellationLifecycleAndTimeBoundaries() throws Exception {
        String admin = login(fixtureUser("ADMIN"), PASSWORD);
        String first = login(fixtureUser("STUDENT"), PASSWORD);
        String second = login(fixtureUser("STUDENT"), PASSWORD);
        long id = fixtureActivity();
        String signup = "/activities/" + id + "/registrations";
        expect(401, request("POST", signup, null, null));
        expect(403, request("POST", "/activities/" + id + "/cancel", first, null));
        long registration = expect(200, request("POST", signup, first, null)).path("registrationId").asLong();
        assertQuotaInvariant(id, 9, 1);
        String cancel = "/activities/registrations/" + registration + "/cancel";
        expect(404, request("POST", cancel, second, null));
        expect(200, request("POST", cancel, first, null));
        expect(200, request("POST", cancel, first, null));
        assertQuotaInvariant(id, 10, 0);
        assertEquals(registration, expect(200, request("POST", signup, first, null)).path("registrationId").asLong());
        assertNull(jdbc.queryForObject("SELECT cancel_time FROM registration WHERE id=?", java.sql.Timestamp.class, registration));
        expect(200, request("POST", signup, second, null));
        assertQuotaInvariant(id, 8, 2);
        // Prime cache before cancellation to verify transaction-commit invalidation.
        expect(200, request("GET", "/activities/" + id, null, null));
        expect(200, request("POST", "/activities/" + id + "/cancel", admin, null));
        expect(200, request("POST", "/activities/" + id + "/cancel", admin, null));
        assertQuotaInvariant(id, 10, 0);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM registration WHERE activity_id=? AND status='CANCELLED' AND cancel_time IS NOT NULL", Integer.class, id));
        expect(404, request("GET", "/activities/" + id, null, null));
        assertEquals("ACTIVITY_NOT_PUBLISHED", expect(409, request("POST", signup, first, null)).path("code").asText());
        expect(409, request("POST", "/activities/" + id + "/publish", admin, null));

        long future = fixtureActivity();
        jdbc.update("UPDATE activity SET registration_start_time=NOW()+INTERVAL 1 DAY WHERE id=?", future);
        assertEquals("REGISTRATION_NOT_STARTED", expect(409, request("POST", "/activities/" + future + "/registrations", first, null)).path("code").asText());
        long closed = fixtureActivity();
        jdbc.update("UPDATE activity SET registration_end_time=NOW()-INTERVAL 1 HOUR WHERE id=?", closed);
        assertEquals("REGISTRATION_CLOSED", expect(409, request("POST", "/activities/" + closed + "/registrations", first, null)).path("code").asText());
        assertQuotaInvariant(future, 10, 0);
        assertQuotaInvariant(closed, 10, 0);
        long started = fixtureActivity();
        long startedRegistration = expect(200, request("POST", "/activities/" + started + "/registrations", first, null)).path("registrationId").asLong();
        jdbc.update("UPDATE activity SET start_time=NOW()-INTERVAL 1 HOUR, registration_end_time=NOW()-INTERVAL 2 HOUR WHERE id=?", started);
        assertEquals("CANCELLATION_CLOSED", expect(409, request("POST", "/activities/registrations/" + startedRegistration + "/cancel", first, null)).path("code").asText());
        expect(409, request("POST", "/activities/" + started + "/cancel", admin, null));
        assertQuotaInvariant(started, 9, 1);
    }

    @Test
    void activitySearchAndCategoryLifecycle() throws Exception {
        String marker = "search_" + UUID.randomUUID().toString().replace("-", "");
        long open = fixtureActivity();
        long upcoming = fixtureActivity();
        long closed = fixtureActivity();
        long draft = fixtureActivity();
        long cancelled = fixtureActivity();
        for (long id : List.of(open, upcoming, closed, draft, cancelled)) {
            jdbc.update("UPDATE activity SET title=?, category='SPORTS' WHERE id=?", marker, id);
        }
        jdbc.update("UPDATE activity SET title=? WHERE id=?", marker + "%", open);
        jdbc.update("UPDATE activity SET category='CLUB', registration_start_time=NOW()+INTERVAL 1 DAY WHERE id=?", upcoming);
        jdbc.update("UPDATE activity SET registration_end_time=NOW()-INTERVAL 1 HOUR WHERE id=?", closed);
        jdbc.update("UPDATE activity SET status='DRAFT' WHERE id=?", draft);
        jdbc.update("UPDATE activity SET status='CANCELLED' WHERE id=?", cancelled);
        String path = "/activities?keyword=" + marker;
        JsonNode page = expect(200, request("GET", path + "&pageSize=1", null, null));
        assertEquals(3, page.path("total").asLong());
        assertEquals(1, page.path("records").size());
        assertEquals(closed, page.path("records").get(0).path("id").asLong());
        assertEquals(upcoming, expect(200, request("GET", path + "&pageSize=1&page=2", null, null))
                .path("records").get(0).path("id").asLong());
        assertEquals(2, expect(200, request("GET", path + "&category=SPORTS", null, null)).path("total").asLong());
        for (String phase : List.of("OPEN", "NOT_STARTED", "CLOSED")) {
            assertEquals(1, expect(200, request("GET", path + "&registrationPhase=" + phase, null, null)).path("total").asLong());
        }
        assertEquals(0, expect(200, request("GET", path + "&category=CLUB&registrationPhase=OPEN", null, null)).path("total").asLong());
        assertEquals(1, expect(200, request("GET", path + "%25", null, null)).path("total").asLong());
        JsonNode beyond = expect(200, request("GET", path + "&page=99", null, null));
        assertEquals(3, beyond.path("total").asLong());
        assertEquals(0, beyond.path("records").size());
        assertEquals(3, expect(200, request("GET", path + "&category=%20&registrationPhase=", null, null)).path("total").asLong());
        expect(400, request("GET", path + "&category=UNKNOWN", null, null));
        expect(400, request("GET", path + "&registrationPhase=UNKNOWN", null, null));
        expect(400, request("GET", "/activities?keyword=" + "a".repeat(101), null, null));
        expect(400, request("GET", path + "&page=0", null, null));
        assertEquals("SPORTS", expect(200, request("GET", "/activities/" + open, null, null)).path("category").asText());
        // Cache reads must preserve the new field too.
        assertEquals("SPORTS", expect(200, request("GET", "/activities/" + open, null, null)).path("category").asText());

        String token = login(fixtureUser("ADMIN"), PASSWORD);
        var now = java.time.LocalDateTime.now();
        var body = new java.util.HashMap<String, Object>();
        body.put("title", marker + "created");
        body.put("location", "测试教室");
        body.put("startTime", now.plusDays(7).toString());
        body.put("endTime", now.plusDays(8).toString());
        body.put("registrationStartTime", now.minusDays(1).toString());
        body.put("registrationEndTime", now.plusDays(6).toString());
        body.put("totalQuota", 10);
        long created = expect(201, request("POST", "/activities", token, body)).path("activityId").asLong();
        activities.add(created);
        assertEquals("OTHER", expect(200, request("GET", "/activities/" + created + "/management", token, null)).path("category").asText());
        body.put("category", "INVALID");
        expect(400, request("PUT", "/activities/" + created, token, body));
        body.put("category", "LECTURE");
        expect(200, request("PUT", "/activities/" + created, token, body));
        expect(200, request("POST", "/activities/" + created + "/publish", token, null));
        assertEquals("LECTURE", expect(200, request("GET", "/activities/" + created, null, null)).path("category").asText());
        JsonNode filtered = expect(200, request("GET", path + "&category=LECTURE&registrationPhase=OPEN", null, null));
        assertEquals(1, filtered.path("total").asLong());
        assertEquals(created, filtered.path("records").get(0).path("id").asLong());
    }

    @Test
    void registrationProfileAndPasswordLifecycle() throws Exception {
        String studentNo = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var body = Map.of("studentNo", studentNo, "name", "新学生", "password", PASSWORD);
        JsonNode created = expect(201, request("POST", "/auth/register", "expired-token", body));
        long id = created.path("userId").asLong();
        users.add(id);
        Account account = new Account(id, studentNo);
        assertEquals("STUDENT", jdbc.queryForObject("SELECT role FROM campus_user WHERE id=?", String.class, id));
        String hash = jdbc.queryForObject("SELECT password_hash FROM campus_user WHERE id=?", String.class, id);
        assertNotEquals(PASSWORD, hash);
        assertTrue(encoder.matches(PASSWORD, hash));
        assertEquals("STUDENT_NO_EXISTS", expect(409, request("POST", "/auth/register", null, body)).path("code").asText());
        expect(400, request("POST", "/auth/register", null, Map.of("studentNo", "bad space", "name", "x", "password", PASSWORD)));
        expect(400, request("POST", "/auth/register", null, Map.of("studentNo", "short", "name", "x", "password", "short")));
        expect(401, request("POST", "/auth/login", null, Map.of("studentNo", studentNo, "password", "WrongPassword!")));
        String token = login(account, PASSWORD);
        String secondToken = login(account, PASSWORD);
        JsonNode profile = expect(200, request("GET", "/users/me", token, null));
        assertEquals(id, profile.path("id").asLong());
        assertFalse(profile.has("passwordHash"));
        assertEquals("STUDENT", profile.path("role").asText());
        // Attempted privilege escalation is never applied, whether unknown fields are rejected or ignored.
        Result forgedProfile = request("PUT", "/users/me", token, Map.of("name", "修改姓名", "role", "ADMIN"));
        assertTrue(forgedProfile.status() == 200 || forgedProfile.status() == 400);
        assertEquals("STUDENT", jdbc.queryForObject("SELECT role FROM campus_user WHERE id=?", String.class, id));
        expect(200, request("PUT", "/users/me", token, Map.of("name", "修改姓名")));
        assertEquals("修改姓名", expect(200, request("GET", "/auth/me", token, null)).path("name").asText());
        expect(400, request("PUT", "/users/me", token, Map.of("name", "  ")));
        expect(401, request("GET", "/users/me", null, null));
        String newPassword = "ChangedPassword123!";
        expect(400, request("PUT", "/users/me/password", token,
                Map.of("oldPassword", "WrongPassword!", "newPassword", newPassword)));
        expect(400, request("PUT", "/users/me/password", token,
                Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD)));
        expect(200, request("PUT", "/users/me/password", token,
                Map.of("oldPassword", PASSWORD, "newPassword", newPassword)));
        expect(401, request("GET", "/users/me", token, null));
        expect(401, request("GET", "/users/me", secondToken, null));
        expect(401, request("POST", "/auth/login", null, Map.of("studentNo", studentNo, "password", PASSWORD)));
        String newToken = login(account, newPassword);
        expect(200, request("POST", "/auth/logout", newToken, null));
        expect(401, request("GET", "/users/me", newToken, null));
    }

    @Test
    void registrationPaginationFiltersAndPermissions() throws Exception {
        Account admin = fixtureUser("ADMIN");
        Account first = fixtureUser("STUDENT");
        Account second = fixtureUser("STUDENT");
        String adminToken = login(admin, PASSWORD);
        String firstToken = login(first, PASSWORD);
        String secondToken = login(second, PASSWORD);
        long activityId = fixtureActivity();
        long emptyActivityId = fixtureActivity();
        long otherActivityId = fixtureActivity();
        long cancelledId = expect(200, request("POST", "/activities/" + activityId + "/registrations", firstToken, null))
                .path("registrationId").asLong();
        expect(200, request("POST", "/activities/" + activityId + "/registrations", secondToken, null));
        expect(200, request("POST", "/activities/" + otherActivityId + "/registrations", firstToken, null));
        expect(200, request("POST", "/activities/registrations/" + cancelledId + "/cancel", firstToken, null));
        String roster = "/activities/" + activityId + "/registrations";
        expect(401, request("GET", roster, null, null));
        expect(403, request("GET", roster, firstToken, null));
        JsonNode all = expect(200, request("GET", roster + "?pageSize=1", adminToken, null));
        assertEquals(2, all.path("total").asLong());
        assertEquals(1, all.path("records").size());
        assertEquals(second.id(), all.path("records").get(0).path("userId").asLong());
        assertEquals(second.studentNo(), all.path("records").get(0).path("studentNo").asText());
        assertFalse(all.path("records").get(0).has("passwordHash"));
        assertEquals(1, expect(200, request("GET", roster + "?status=REGISTERED", adminToken, null)).path("total").asLong());
        JsonNode cancelled = expect(200, request("GET", roster + "?status=CANCELLED", adminToken, null));
        assertEquals(1, cancelled.path("total").asLong());
        assertEquals(cancelledId, cancelled.path("records").get(0).path("registrationId").asLong());
        assertFalse(cancelled.path("records").get(0).path("cancelTime").isNull());
        JsonNode beyond = expect(200, request("GET", roster + "?page=99", adminToken, null));
        assertEquals(2, beyond.path("total").asLong());
        assertEquals(0, beyond.path("records").size());
        JsonNode empty = expect(200, request("GET", "/activities/" + emptyActivityId + "/registrations", adminToken, null));
        assertEquals(0, empty.path("total").asLong());
        expect(404, request("GET", "/activities/9223372036854775807/registrations", adminToken, null));
        expect(400, request("GET", roster + "?status=ABC", adminToken, null));
        expect(400, request("GET", roster + "?page=0", adminToken, null));
        expect(400, request("GET", roster + "?pageSize=101", adminToken, null));
        String mine = "/users/me/registrations";
        JsonNode own = expect(200, request("GET", mine + "?pageSize=1&userId=" + second.id(), firstToken, null));
        assertEquals(2, own.path("total").asLong());
        assertEquals(1, own.path("records").size());
        JsonNode ownCancelled = expect(200, request("GET", mine + "?status=CANCELLED", firstToken, null));
        assertEquals(1, ownCancelled.path("total").asLong());
        assertEquals(cancelledId, ownCancelled.path("records").get(0).path("registrationId").asLong());
        assertEquals(1, expect(200, request("GET", mine, secondToken, null)).path("total").asLong());
        expect(401, request("GET", mine, null, null));
        expect(400, request("GET", mine + "?page=abc", firstToken, null));
        expect(400, request("GET", mine + "?status=DRAFT", firstToken, null));
    }
}
