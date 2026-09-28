package com.campus.ticket;

import com.campus.ticket.cache.ActivityBloomFilter;
import com.campus.ticket.cache.VenueCacheStore;
import com.campus.ticket.cache.VenueLocalCache;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.VenueCacheData;
import com.campus.ticket.mapper.VenueMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real HTTP/MySQL/Redis verification. The additive venue table remains; only fixtures are removed. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_VENUE_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=", "spring.kafka.listener.auto-startup=false",
        "campus.booking.jobs-enabled=false", "campus.booking.redis-sync-enabled=false",
        "logging.level.org.springframework.web=INFO", "logging.level.org.springframework.jdbc=INFO"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VenueIntegrationTest
{
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper json;
    @Autowired VenueLocalCache local;
    @Autowired VenueCacheStore store;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean VenueMapper mapper;
    // Do not scan or alter the unrelated activity filter while testing venues.
    @MockitoBean ActivityBloomFilter activityBloomFilter;

    final String run = UUID.randomUUID().toString().substring(0, 8);
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> venues = new ArrayList<>();
    final List<Long> users = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    String adminToken;
    String studentToken;
    record Response(int status, JsonNode body) { }

    @BeforeAll
    void prepare() throws Exception
    {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection())
        {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/20260928_add_venue.sql"));
        }
        adminToken = account("ADMIN");
        studentToken = account("STUDENT");
    }

    @Test
    @Order(1)
    void permissionsAndValidation() throws Exception
    {
        Map<String, Object> body = body("OPEN");
        expect(401, request("POST", "/venues", null, body));
        expect(403, request("POST", "/venues", studentToken, body));
        expect(403, request("GET", "/venues/management", studentToken, null));
        expect(401, request("GET", "/venues/1/management", null, null));
        Map<String, Object> invalid = new HashMap<>(body);
        invalid.put("capacity", 0);
        expect(400, request("POST", "/venues", adminToken, invalid));
        expect(400, request("GET", "/venues?page=0", null, null));
        expect(400, request("GET", "/venues/0", null, null));
        expect(400, request("GET", "/venues/management?status=UNKNOWN", adminToken, null));
        System.out.println("VENUE_IT permissions-and-validation=passed");
    }

    @Test
    @Order(2)
    void lifecycleAndCacheConsistency() throws Exception
    {
        long id = create();
        clearInvocations(mapper);
        String path = "/venues/" + id;
        expect(200, request("GET", path, null, null));
        expect(200, request("GET", path, null, null));
        long hits = local.stats().hitCount();
        expect(200, request("GET", path, null, null));
        assertTrue(local.stats().hitCount() > hits);
        verify(mapper, times(1)).findOpenById(id);
        local.invalidate(id);
        expect(200, request("GET", path, null, null));
        verify(mapper, times(1)).findOpenById(id);

        String oldVersion = store.getVersion(id);
        String oldJson = redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id);
        Map<String, Object> changed = new HashMap<>(body("OPEN"));
        changed.put("name", "Updated-" + run);
        expect(200, request("PUT", path, adminToken, changed));
        assertNull(local.get(id));
        assertNull(redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id));
        assertFalse(store.writeIfVersionMatches(id, oldVersion, oldJson, Duration.ofMinutes(1)));
        assertEquals("Updated-" + run, expect(200, request("GET", path, null, null)).path("name").asText());

        expect(200, request("PUT", path, adminToken, body("CLOSED")));
        expect(404, request("GET", path, null, null));
        assertEquals("", redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id));
        assertEquals("CLOSED", expect(200, request("GET", path + "/management", adminToken, null)).path("status").asText());
        assertEquals(0L, expect(200, request("GET", "/venues?keyword=" + run, null, null)).path("total").asLong());
        assertEquals(1L, expect(200, request("GET", "/venues/management?status=CLOSED&keyword=" + run, adminToken, null)).path("total").asLong());

        expect(200, request("PUT", path, adminToken, body("OPEN")));
        expect(200, request("GET", path, null, null));
        assertEquals(1L, expect(200, request("GET", "/venues?keyword=" + run, null, null)).path("total").asLong());
        System.out.println("VENUE_IT lifecycle-l1-l2-negative-cache-version-guard=passed");
    }

    @Test
    @Order(3)
    void concurrentColdReadsUseOneDatabaseLoad() throws Exception
    {
        long id = create();
        clearInvocations(mapper);
        ExecutorService clients = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(clients.submit(() -> {
                ready.countDown();
                start.await();
                return request("GET", "/venues/" + id, null, null);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Response> result : futures) expect(200, result.get(15, TimeUnit.SECONDS));
            verify(mapper, times(1)).findOpenById(id);
        }
        finally
        {
            start.countDown();
            clients.shutdownNow();
        }
        System.out.println("VENUE_IT 20-concurrent-cold-reads sql=1 status=200");
    }

    @Test
    @Order(4)
    void expiredLogicalCacheReturnsOldValueWhileRefreshing() throws Exception
    {
        long id = create();
        expect(200, request("GET", "/venues/" + id, null, null));
        VenueCacheData old = json.readValue(redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id), VenueCacheData.class);
        redis.opsForValue().set(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id, json.writeValueAsString(new VenueCacheData(old.data(), Instant.now().minusSeconds(1))), Duration.ofMinutes(1));
        local.invalidate(id);
        jdbc.update("UPDATE venue SET name=? WHERE id=?", "Refreshed-" + run, id);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VenueMapper databaseMapper = sqlSession.getMapper(VenueMapper.class);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Refresh test timed out");
            return databaseMapper.findOpenById(id);
        }).when(mapper).findOpenById(id);
        try
        {
            assertEquals(old.data().getName(), expect(200, request("GET", "/venues/" + id, null, null)).path("name").asText());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
        }
        await(() -> {
            String value = redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id);
            return value != null && value.contains("Refreshed-" + run) && local.get(id) == null;
        });
        assertEquals("Refreshed-" + run, expect(200, request("GET", "/venues/" + id, null, null)).path("name").asText());
        System.out.println("VENUE_IT logical-expiry-async-refresh=passed");
    }

    @Test
    @Order(5)
    void absentVenueUsesNegativeCache() throws Exception
    {
        long id = Long.MAX_VALUE - Integer.toUnsignedLong(run.hashCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM venue WHERE id=?", Integer.class, id));
        venues.add(id);
        clearInvocations(mapper);
        expect(404, request("GET", "/venues/" + id, null, null));
        expect(404, request("GET", "/venues/" + id, null, null));
        expect(404, request("GET", "/venues/" + id, null, null));
        verify(mapper, times(1)).findOpenById(id);
        assertEquals("", redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id));
        System.out.println("VENUE_IT missing-id-404-negative-cache sql=1");
    }

    private Map<String, Object> body(String status)
    {
        return Map.of("name", "Venue-" + run, "address", "Campus hall", "capacity", 100, "description", "Integration fixture", "status", status);
    }

    private long create() throws Exception
    {
        long id = expect(201, request("POST", "/venues", adminToken, body("OPEN"))).path("venueId").asLong();
        venues.add(id);
        return id;
    }

    private String account(String role) throws Exception
    {
        String no = "venue_" + run + "_" + role;
        jdbc.update("INSERT INTO campus_user(student_no,name,password_hash,role) VALUES(?,?,?,?)", no, "Venue fixture", encoder.encode("VenueTest123!"), role);
        users.add(jdbc.queryForObject("SELECT id FROM campus_user WHERE student_no=?", Long.class, no));
        String token = expect(200, request("POST", "/auth/login", null, Map.of("studentNo", no, "password", "VenueTest123!"))).path("token").asText();
        tokens.add(token);
        return token;
    }

    private Response request(String method, String path, String token, Object body) throws Exception
    {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }

    private JsonNode expect(int status, Response response)
    {
        assertEquals(status, response.status(), response.body().toString());
        return response.body();
    }

    private void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline)
        {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Cache refresh did not complete");
    }

    @AfterAll
    void cleanup()
    {
        for (Long id : venues)
        {
            local.invalidate(id);
            redis.delete(List.of(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id, RedisConstants.VENUE_CACHE_VERSION_KEY_PREFIX + id));
            jdbc.update("DELETE FROM venue WHERE id=?", id);
        }
        for (String token : tokens) redis.delete(RedisConstants.LOGIN_KEY_PREFIX + token);
        for (Long id : users) jdbc.update("DELETE FROM campus_user WHERE id=?", id);
        System.out.println("VENUE_IT fixtures-cleaned run=" + run);
    }
}
