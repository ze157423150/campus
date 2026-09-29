package com.campus.ticket;

import com.campus.ticket.booking.*;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.job.BookingRedisSyncJob;
import com.campus.ticket.service.BookingCancellationService;
import com.campus.ticket.service.BookingDispatchService;
import com.campus.ticket.service.BookingRedisSyncService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real MySQL/Redis/Kafka tests. Only this run's fixture IDs are dispatched/recovered. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_ASYNC_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("async")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "campus.booking.jobs-enabled=false", "campus.booking.redis-sync-enabled=false",
        "campus.waitlist.jobs-enabled=false",
        "logging.level.org.apache.kafka=WARN", "logging.level.com.campus.ticket.mapper=INFO"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AsyncBookingIntegrationTest
{
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String TOPIC = "campus.it." + RUN;
    private static final String GROUP = TOPIC + ".workers";
    private static final String PASSWORD = "Test" + RUN + "!";

    @DynamicPropertySource
    static void isolatedTopics(DynamicPropertyRegistry registry)
    {
        registry.add("campus.booking.topic", () -> TOPIC);
        registry.add("campus.booking.dead-letter-topic", () -> TOPIC + ".dlt");
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    }

    @LocalServerPort int port;
    @Value("${spring.kafka.bootstrap-servers}") String bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper json;
    @Autowired BookingMapper mapper;
    @Autowired BookingRedisStore store;
    @Autowired BookingCancellationService cancellation;
    @Autowired BookingRedisSyncService sync;
    @Autowired BookingDispatchService dispatchService;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;

    final List<Long> activities = new CopyOnWriteArrayList<>();
    final List<Long> users = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    final List<Account> students = new ArrayList<>();
    final AtomicBoolean recoveryEnabled = new AtomicBoolean(true);
    final AtomicBoolean dispatchEnabled = new AtomicBoolean(true);
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    Account admin;
    AdminClient kafkaAdmin;

    record Account(long id, String studentNo, String token) { }
    record Response(int status, JsonNode body) { }

    @BeforeAll
    void prepare() throws Exception
    {
        System.out.println("ASYNC_IT run=" + RUN + " port=" + port + " topic=" + TOPIC);
        kafkaAdmin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        String adminNo = "it_" + RUN + "_admin";
        jdbc.update("INSERT INTO campus_user(student_no,name,password_hash,role) VALUES(?,?,?,'ADMIN')", adminNo, "Async test admin", encoder.encode(PASSWORD));
        long adminId = jdbc.queryForObject("SELECT id FROM campus_user WHERE student_no=?", Long.class, adminNo);
        users.add(adminId);
        admin = login(adminId, adminNo);

        int userCount = Integer.getInteger("campus.test.users", 20);
        for (int i = 0; i < userCount; i++)
        {
            String no = "it_" + RUN + "_" + i;
            JsonNode created = expect(201, request("POST", "/admin/users", admin.token(), Map.of("studentNo", no, "name", "Async test " + i, "password", PASSWORD)));
            long userId = created.path("userId").asLong();
            users.add(userId);
            students.add(login(userId, no));
        }

        // Run the production job classes, restricting discovery to this run's fixtures.
        // All claims, business writes, Lua scripts and Kafka operations use real infrastructure.
        BookingMapper scoped = mock(BookingMapper.class, delegatesTo(mapper));
        doAnswer(invocation -> activities.stream().filter(id -> id > (long) invocation.getArgument(0)).sorted().limit(20).toList())
                .when(scoped).findDispatchActivityIds(anyLong());
        doAnswer(invocation -> {
            List<String> due = new ArrayList<>();
            for (long id : activities)
            {
                due.addAll(jdbc.queryForList("SELECT order_id FROM booking_order WHERE activity_id=? AND redis_dirty=1 AND status IN ('FAILED','CANCELLED') AND next_check_time<=NOW(3)", String.class, id));
            }
            return due.stream().limit((int) invocation.getArgument(0)).toList();
        }).when(scoped).findDueRedisSyncOrders(anyInt());

        BookingDispatchJob dispatcher = new BookingDispatchJob(scoped, store, dispatchService);
        BookingRedisSyncJob recovery = new BookingRedisSyncJob(scoped, sync);
        scheduler.scheduleWithFixedDelay(() -> {
            if (dispatchEnabled.get()) dispatcher.dispatch();
        }, 0, Long.getLong("campus.test.dispatch-delay-ms", 100L), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            if (recoveryEnabled.get()) recovery.synchronizeDueOrders();
        }, 0, Long.getLong("campus.test.recovery-delay-ms", 200L), TimeUnit.MILLISECONDS);
    }

    @Test
    @Order(1)
    void twentyUsersCompeteForFivePlaces() throws Exception
    {
        long activityId = createActivity(5);
        List<Response> results = concurrentRegistrations(activityId, students);
        assertEquals(5, results.stream().filter(r -> r.status() == 202).count(), results.toString());
        assertEquals(15, results.stream().filter(r -> r.status() == 409 && "QUOTA_EXHAUSTED".equals(r.body().path("code").asText())).count(), results.toString());
        for (Response result : results)
        {
            if (result.status() == 202) awaitSuccess(result.body().path("orderId").asText(), activityId);
        }
        assertState(activityId, 0, 5);
        assertEquals(5, countOrders(activityId, "SUCCEEDED"));
        System.out.println("ASYNC_IT oversell activity=" + activityId + " accepted=5 soldOut=15 dbQuota=0 redisQuota=0 registered=5");
    }

    @Test
    @Order(2)
    void sameUserSubmitsTwentyTimes() throws Exception
    {
        long activityId = createActivity(10);
        List<Response> results = concurrentRegistrations(activityId, Collections.nCopies(20, students.get(0)));
        assertEquals(1, results.stream().filter(r -> r.status() == 202).count(), results.toString());
        assertEquals(19, results.stream().filter(r -> r.status() == 409 && "DUPLICATE_REGISTRATION".equals(r.body().path("code").asText())).count(), results.toString());
        String orderId = results.stream().filter(r -> r.status() == 202).findFirst().orElseThrow().body().path("orderId").asText();
        awaitSuccess(orderId, activityId);
        assertState(activityId, 9, 1);
        assertEquals(1, countOrders(activityId, "SUCCEEDED"));
        System.out.println("ASYNC_IT duplicate-submit activity=" + activityId + " accepted=1 duplicate=19 quota=9 registered=1");
    }

    @Test
    @Order(3)
    void repeatedKafkaDeliveryDoesNotRepeatDatabaseWrites() throws Exception
    {
        long activityId = createActivity(2);
        String orderId = register(activityId, students.get(0));
        awaitSuccess(orderId, activityId);
        BookingMessage message = originalMessage(activityId, orderId);
        for (int i = 0; i < 3; i++) sendAndAwaitConsumption(message);
        assertState(activityId, 1, 1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_order_log WHERE order_id=? AND event_type='SUCCEEDED'", Integer.class, orderId));
        JsonNode mine = expect(200, request("GET", "/activities/" + activityId + "/booking-orders/" + orderId, students.get(0).token(), null));
        assertEquals("SUCCEEDED", mine.path("status").asText());
        expect(404, request("GET", "/activities/" + activityId + "/booking-orders/" + orderId, students.get(1).token(), null));
        System.out.println("ASYNC_IT duplicate-kafka activity=" + activityId + " replayed=3 registered=1 successLogs=1 ownership-check=passed");
    }

    @Test
    @Order(4)
    void cancellationReregistrationAndLateOldMessageAreSafe() throws Exception
    {
        long activityId = createActivity(1);
        Account student = students.get(0);
        String firstOrder = register(activityId, student);
        awaitSuccess(firstOrder, activityId);
        BookingMessage oldMessage = originalMessage(activityId, firstOrder);
        for (int i = 0; i < 2; i++) expect(200, request("POST", cancelPath(activityId, firstOrder), student.token(), null));
        assertState(activityId, 1, 0);
        String secondOrder = register(activityId, student);
        awaitSuccess(secondOrder, activityId);
        expect(200, request("POST", cancelPath(activityId, firstOrder), student.token(), null));
        sendAndAwaitConsumption(oldMessage);
        assertState(activityId, 0, 1);
        assertEquals("CANCELLED", mapper.find(firstOrder).getStatus());
        assertEquals("SUCCEEDED", mapper.find(secondOrder).getStatus());
        assertEquals(secondOrder, redis.opsForHash().get(RedisConstants.bookingInventoryKey(activityId), "u:" + student.id()));
        System.out.println("ASYNC_IT cancellation activity=" + activityId + " repeated-cancel=passed reregister=passed late-message=passed");
    }

    @Test
    @Order(5)
    void backgroundRecoversCancellationAfterDatabaseCommit() throws Exception
    {
        recoveryScenario(false);
    }

    @Test
    @Order(6)
    void backgroundReplaysLuaAfterRedisSucceededButDirtyWasNotCleared() throws Exception
    {
        recoveryScenario(true);
    }

    @Test
    @Order(7)
    @EnabledIfEnvironmentVariable(named = "CAMPUS_ASYNC_STRESS_TESTS", matches = "true")
    void highConcurrencyStability() throws Exception
    {
        assertTrue(students.size() >= 200, "Use -Dcampus.test.users=200");
        assertEquals(1000L, Long.getLong("campus.test.dispatch-delay-ms", 100L));
        List<Map<String, Object>> measurements = new ArrayList<>();
        runLoadStage("warmup", 20, 20, 1, measurements);
        runLoadStage("burst-50", 50, 25, 1, measurements);
        runLoadStage("burst-100", 100, 50, 1, measurements);
        runLoadStage("burst-200", 200, 100, 1, measurements);
        runLoadStage("repeat-200x5", 200, 200, 5, measurements);
    }

    record TimedResponse(Response response, long latencyNanos) { }

    private void runLoadStage(String name, int concurrency, int quota, int waves, List<Map<String, Object>> measurements) throws Exception
    {
        long activityId = createActivity(quota);
        AtomicLong maxPending = new AtomicLong();
        AtomicLong peakHeap = new AtomicLong();
        AtomicLong peakThreads = new AtomicLong();
        AtomicLong sampleErrors = new AtomicLong();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleWithFixedDelay(() -> {
            try
            {
                Long pending = redis.opsForZSet().zCard(RedisConstants.bookingPendingKey(activityId));
                maxPending.accumulateAndGet(pending == null ? 0 : pending, Math::max);
                peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                peakThreads.accumulateAndGet(ManagementFactory.getThreadMXBean().getThreadCount(), Math::max);
            }
            catch (RuntimeException e) { sampleErrors.incrementAndGet(); }
        }, 0, 200, TimeUnit.MILLISECONDS);

        long start = System.nanoTime();
        List<TimedResponse> responses = new ArrayList<>();
        ExecutorService clients = Executors.newFixedThreadPool(concurrency);
        try
        {
            for (int wave = 0; wave < waves; wave++)
            {
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<TimedResponse>> futures = new ArrayList<>();
                for (Account student : students.subList(0, concurrency))
                {
                    futures.add(clients.submit(() -> {
                        ready.countDown();
                        if (!go.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Load barrier timeout");
                        long requestStart = System.nanoTime();
                        Response response;
                        try { response = request("POST", "/activities/" + activityId + "/registrations", student.token(), null); }
                        catch (Exception e) { response = new Response(0, json.readTree("{\"code\":\"TRANSPORT_ERROR\"}")); }
                        return new TimedResponse(response, System.nanoTime() - requestStart);
                    }));
                }
                boolean allReady = ready.await(15, TimeUnit.SECONDS);
                go.countDown();
                assertTrue(allReady, "Client threads did not reach start barrier");
                for (Future<TimedResponse> future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
            }

            double httpSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            long accepted = responses.stream().filter(r -> r.response().status() == 202).count();
            long duplicate = responses.stream().filter(r -> "DUPLICATE_REGISTRATION".equals(r.response().body().path("code").asText())).count();
            long soldOut = responses.stream().filter(r -> "QUOTA_EXHAUSTED".equals(r.response().body().path("code").asText())).count();
            long unexpected = responses.size() - accepted - duplicate - soldOut;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
            boolean drained = false;
            while (System.nanoTime() < deadline)
            {
                int terminal = jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE activity_id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED')", Integer.class, activityId);
                Long pending = redis.opsForZSet().zCard(RedisConstants.bookingPendingKey(activityId));
                if (terminal == accepted && Long.valueOf(0).equals(pending)) { drained = true; break; }
                Thread.sleep(200);
            }

            List<Long> latencies = responses.stream().map(TimedResponse::latencyNanos).sorted().toList();
            List<Long> acceptedLatencies = responses.stream().filter(r -> r.response().status() == 202).map(TimedResponse::latencyNanos).sorted().toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stage", name);
            result.put("activityId", activityId);
            result.put("concurrency", concurrency);
            result.put("requests", responses.size());
            result.put("quota", quota);
            result.put("accepted", accepted);
            result.put("duplicate", duplicate);
            result.put("soldOut", soldOut);
            result.put("unexpectedHttpOrTransport", unexpected);
            result.put("httpBatchSeconds", httpSeconds);
            result.put("httpBatchRequestsPerSecond", responses.size() / httpSeconds);
            result.put("httpP50Ms", percentileMillis(latencies, 0.50));
            result.put("httpP95Ms", percentileMillis(latencies, 0.95));
            result.put("httpP99Ms", percentileMillis(latencies, 0.99));
            result.put("httpMaxMs", percentileMillis(latencies, 1));
            result.put("acceptedHttpP95Ms", percentileMillis(acceptedLatencies, 0.95));
            result.put("endToEndBatchSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
            result.put("drained", drained);
            result.put("succeeded", countOrders(activityId, "SUCCEEDED"));
            result.put("failed", countOrders(activityId, "FAILED"));
            result.put("registered", jdbc.queryForObject("SELECT COUNT(*) FROM registration WHERE activity_id=? AND status='REGISTERED'", Integer.class, activityId));
            result.put("databaseQuota", jdbc.queryForObject("SELECT remaining_quota FROM activity WHERE id=?", Integer.class, activityId));
            result.put("redisQuota", redis.opsForHash().get(RedisConstants.bookingInventoryKey(activityId), "quota"));
            result.put("pendingPeakSampled", maxPending.get());
            result.put("pendingEnd", redis.opsForZSet().zCard(RedisConstants.bookingPendingKey(activityId)));
            result.put("combinedClientServerHeapPeakMiB", peakHeap.get() / 1048576.0);
            result.put("combinedClientServerThreadPeak", peakThreads.get());
            result.put("samplerErrors", sampleErrors.get());
            result.put("duplicateSuccessLogOrders", jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT l.order_id FROM booking_order_log l JOIN booking_order o ON o.order_id=l.order_id WHERE o.activity_id=? AND l.event_type='SUCCEEDED' GROUP BY l.order_id HAVING COUNT(*)>1) duplicates", Integer.class, activityId));

            Map<TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> dltPartitions = new HashMap<>();
            for (int partition = 0; partition < 3; partition++) dltPartitions.put(new TopicPartition(TOPIC + ".dlt", partition), org.apache.kafka.clients.admin.OffsetSpec.latest());
            long dltRecords = kafkaAdmin.listOffsets(dltPartitions).all().get(10, TimeUnit.SECONDS).values().stream().mapToLong(v -> v.offset()).sum();
            result.put("deadLetterRecordsCumulative", dltRecords);
            measurements.add(result);
            Files.writeString(Path.of("target", "async-load-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run", RUN, "topic", TOPIC, "measurements", measurements)));
            System.out.println("ASYNC_LOAD " + json.writeValueAsString(result));

            assertEquals(0, unexpected, result.toString());
            assertEquals(quota, accepted, result.toString());
            assertTrue(drained, result.toString());
            assertEquals(quota, countOrders(activityId, "SUCCEEDED"), result.toString());
            assertEquals(0, countOrders(activityId, "FAILED"), result.toString());
            assertEquals(0L, dltRecords, result.toString());
            assertEquals(0, result.get("duplicateSuccessLogOrders"));
            assertState(activityId, 0, quota);
            Map<Object, Object> requests = redis.opsForHash().entries(RedisConstants.bookingRequestsKey(activityId));
            assertEquals(quota, requests.size());
            for (Object requestJson : requests.values()) assertEquals("SUCCEEDED", json.readTree(requestJson.toString()).path("status").asText());
        }
        finally
        {
            clients.shutdownNow();
            sampler.shutdownNow();
            sampler.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(8)
    void immediateDispatchAndAtomicClaimRecovery() throws Exception
    {
        dispatchEnabled.set(false);
        try
        {
            long activityId = createActivity(2);
            String immediateOrder = register(activityId, students.get(0));
            awaitSuccess(immediateOrder, activityId);

            // Simulate interruption after reserve and before the immediate send.
            String delayedOrder = UUID.randomUUID().toString();
            assertTrue(store.reserve(activityId, students.get(1).id(), delayedOrder, 1L, Instant.now().plusSeconds(120)).success());
            ExecutorService contenders = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try
            {
                Future<BookingDispatchMessage> direct = contenders.submit(() -> {
                    start.await();
                    return store.claimRequest(activityId, delayedOrder);
                });
                Future<BookingDispatchMessage> scheduled = contenders.submit(() -> {
                    start.await();
                    return store.claimDueRequest(activityId);
                });
                start.countDown();
                BookingDispatchMessage first = direct.get(10, TimeUnit.SECONDS);
                BookingDispatchMessage second = scheduled.get(10, TimeUnit.SECONDS);
                assertEquals(1, (first == null ? 0 : 1) + (second == null ? 0 : 1));
                assertEquals(delayedOrder, (first == null ? second : first).orderId());
                assertNull(store.claimRequest(activityId, delayedOrder));
                assertNull(store.claimDueRequest(activityId));
            }
            finally
            {
                contenders.shutdownNow();
            }

            // Simulate lease expiry after the winning claimant stopped before sending.
            redis.opsForZSet().add(RedisConstants.bookingPendingKey(activityId), delayedOrder, 0);
            dispatchEnabled.set(true);
            awaitSuccess(delayedOrder, activityId);
            assertState(activityId, 0, 2);
            System.out.println("ASYNC_IT immediate-without-job=passed claim-race=passed expired-claim-recovery=passed");
        }
        finally
        {
            dispatchEnabled.set(true);
        }
    }

    private double percentileMillis(List<Long> sorted, double quantile)
    {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private void recoveryScenario(boolean redisAlreadyDone) throws Exception
    {
        long activityId = createActivity(1);
        Account student = students.get(0);
        String orderId = register(activityId, student);
        awaitSuccess(orderId, activityId);
        recoveryEnabled.set(false);
        try
        {
            UserHolder.saveUser(new LoginUser(student.id(), student.studentNo(), "Test", "STUDENT"));
            BookingOrder order;
            try { order = cancellation.cancelInDatabase(activityId, orderId); }
            finally { UserHolder.removeUser(); }
            assertEquals(Boolean.TRUE, mapper.find(orderId).getRedisDirty());
            assertEquals(1, jdbc.queryForObject("SELECT remaining_quota FROM activity WHERE id=?", Integer.class, activityId));
            if (redisAlreadyDone) store.markCancelled(activityId, student.id(), orderId, order.getEpoch(), order.getRegistrationId());
            assertEquals(redisAlreadyDone ? "1" : "0", redis.opsForHash().get(RedisConstants.bookingInventoryKey(activityId), "quota"));
        }
        finally { recoveryEnabled.set(true); }

        // No HTTP cancellation retry: only the periodic production recovery job finishes this work.
        await("background recovery " + orderId, () -> Boolean.FALSE.equals(mapper.find(orderId).getRedisDirty()));
        assertState(activityId, 1, 0);
        assertEquals("CANCELLED", json.readTree(store.findRequestJson(activityId, orderId)).path("status").asText());
        sync.synchronize(orderId);
        assertState(activityId, 1, 0);
        System.out.println("ASYNC_IT recovery activity=" + activityId + " redisAlreadyDone=" + redisAlreadyDone + " no-user-retry=passed dirty=0 quota=1");
    }

    private long createActivity(int quota) throws Exception
    {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> body = Map.of("title", "ASYNC-IT-" + RUN, "category", "LECTURE", "location", "Test room",
                "startTime", now.plusDays(2).toString(), "endTime", now.plusDays(2).plusHours(1).toString(),
                "registrationStartTime", now.minusHours(1).toString(), "registrationEndTime", now.plusDays(1).toString(), "totalQuota", quota);
        long id = expect(201, request("POST", "/activities", admin.token(), body)).path("activityId").asLong();
        activities.add(id);
        expect(200, request("POST", "/activities/" + id + "/publish-with-inventory", admin.token(), null));
        return id;
    }

    private Account login(long id, String no) throws Exception
    {
        String token = expect(200, request("POST", "/auth/login", null, Map.of("studentNo", no, "password", PASSWORD))).path("token").asText();
        tokens.add(token);
        return new Account(id, no, token);
    }

    private String register(long activityId, Account user) throws Exception
    {
        return expect(202, request("POST", "/activities/" + activityId + "/registrations", user.token(), null)).path("orderId").asText();
    }

    private List<Response> concurrentRegistrations(long activityId, List<Account> accounts) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(accounts.size());
        CountDownLatch ready = new CountDownLatch(accounts.size());
        CountDownLatch go = new CountDownLatch(1);
        try
        {
            List<Future<Response>> futures = new ArrayList<>();
            for (Account account : accounts) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                return request("POST", "/activities/" + activityId + "/registrations", account.token(), null);
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<Response> results = new ArrayList<>();
            for (Future<Response> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
        finally { go.countDown(); pool.shutdownNow(); }
    }

    private void awaitSuccess(String orderId, long activityId) throws Exception
    {
        await("order success " + orderId, () -> {
            BookingOrder order = mapper.find(orderId);
            String value = store.findRequestJson(activityId, orderId);
            return order != null && "SUCCEEDED".equals(order.getStatus()) && value != null
                    && "SUCCEEDED".equals(json.readTree(value).path("status").asText())
                    && redis.opsForZSet().score(RedisConstants.bookingPendingKey(activityId), orderId) == null;
        });
    }

    private BookingMessage originalMessage(long activityId, String orderId)
    {
        JsonNode request = json.readTree(store.findRequestJson(activityId, orderId));
        return new BookingMessage(orderId, Long.valueOf(request.path("userId").asText()), activityId, 1L, "PENDING",
                request.path("acceptedAtMillis").asLong(), request.path("expiresAtMillis").asLong());
    }

    private void sendAndAwaitConsumption(BookingMessage message) throws Exception
    {
        var metadata = kafka.send(TOPIC, message.orderId(), json.writeValueAsString(message)).get(20, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(TOPIC, metadata.partition());
        await("consumer offset " + metadata.offset(), () -> {
            try
            {
                var offsets = kafkaAdmin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                return offsets.containsKey(partition) && offsets.get(partition).offset() > metadata.offset();
            }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private void assertState(long activityId, int remaining, int registered)
    {
        assertEquals(remaining, jdbc.queryForObject("SELECT remaining_quota FROM activity WHERE id=?", Integer.class, activityId));
        assertEquals(Integer.toString(remaining), redis.opsForHash().get(RedisConstants.bookingInventoryKey(activityId), "quota"));
        assertEquals(registered, jdbc.queryForObject("SELECT COUNT(*) FROM registration WHERE activity_id=? AND status='REGISTERED'", Integer.class, activityId));
        assertEquals(0L, redis.opsForZSet().zCard(RedisConstants.bookingPendingKey(activityId)));
    }

    private int countOrders(long activityId, String status)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE activity_id=? AND status=?", Integer.class, activityId, status);
    }

    private Response request(String method, String path, String token, Object body) throws Exception
    {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(result.statusCode(), result.body().isBlank() ? json.readTree("null") : json.readTree(result.body()));
    }

    private JsonNode expect(int status, Response response)
    {
        assertEquals(status, response.status(), response.body().toString());
        return response.body();
    }

    private String cancelPath(long activityId, String orderId)
    {
        return "/activities/" + activityId + "/booking-orders/" + orderId + "/cancel";
    }

    private void await(String description, BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline)
        {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("Timed out: " + description);
    }

    @AfterAll
    void cleanup() throws Exception
    {
        scheduler.shutdownNow();
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
        listeners.stop();
        if (kafkaAdmin != null)
        {
            try { kafkaAdmin.deleteTopics(List.of(TOPIC, TOPIC + ".dlt")).all().get(20, TimeUnit.SECONDS); }
            finally { kafkaAdmin.close(Duration.ofSeconds(5)); }
        }
        for (long id : activities)
        {
            for (Long quotaId : jdbc.queryForList("SELECT id FROM waitlist_quota WHERE activity_id=?", Long.class, id))
            {
                jdbc.update("DELETE FROM waitlist_redis_task WHERE quota_id=?", quotaId);
                redis.delete(RedisConstants.waitlistQuotaKey(id, quotaId));
            }
            jdbc.update("DELETE FROM waitlist_notification WHERE activity_id=?", id);
            jdbc.update("DELETE o FROM waitlist_offer o JOIN activity_waitlist w ON w.id=o.waitlist_id WHERE w.activity_id=?", id);
            jdbc.update("DELETE FROM waitlist_quota WHERE activity_id=?", id);
            jdbc.update("DELETE FROM activity_waitlist WHERE activity_id=?", id);
            redis.delete(List.of(RedisConstants.bookingInventoryKey(id), RedisConstants.bookingRequestsKey(id), RedisConstants.bookingPendingKey(id),
                    RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + id, RedisConstants.ACTIVITY_CACHE_VERSION_KEY_PREFIX + id));
            jdbc.update("DELETE FROM booking_order_log WHERE order_id IN (SELECT order_id FROM booking_order WHERE activity_id=?)", id);
            jdbc.update("DELETE FROM booking_outbox WHERE activity_id=?", id);
            jdbc.update("DELETE FROM booking_order WHERE activity_id=?", id);
            jdbc.update("DELETE FROM booking_inventory WHERE activity_id=?", id);
            jdbc.update("DELETE FROM registration WHERE activity_id=?", id);
            jdbc.update("DELETE FROM activity WHERE id=?", id);
        }
        for (String token : tokens) redis.delete(RedisConstants.LOGIN_KEY_PREFIX + token);
        for (long id : users) jdbc.update("DELETE FROM campus_user WHERE id=?", id);
        System.out.println("ASYNC_IT cleanup completed run=" + RUN + " activities=" + activities.size() + " users=" + users.size());
    }
}
