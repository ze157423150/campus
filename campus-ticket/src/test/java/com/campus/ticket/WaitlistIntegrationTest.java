package com.campus.ticket;

import com.campus.ticket.booking.*;
import com.campus.ticket.cache.ActivityBloomFilter;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.*;
import com.campus.ticket.entity.*;
import com.campus.ticket.event.WaitlistOfferReadyEvent;
import com.campus.ticket.mapper.*;
import com.campus.ticket.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CAMPUS_WAITLIST_INTEGRATION_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.profiles.active=", "spring.kafka.listener.auto-startup=false", "campus.waitlist.jobs-enabled=false", "campus.booking.jobs-enabled=false", "campus.booking.redis-sync-enabled=false", "logging.level.org.springframework=INFO", "logging.level.com.campus.ticket=INFO"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistIntegrationTest
{
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedissonClient redisson;
    @Autowired JsonMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired BookingRedisStore bookingRedis;
    @Autowired BookingConsumeService consumer;
    @Autowired BookingCancellationService cancel;
    @Autowired BookingRedisSyncService sync;
    @Autowired WaitlistService waiting;
    @Autowired WaitlistWorkflowService workflow;
    @Autowired WaitlistCoordinator coordinator;
    @Autowired WaitlistWorkflowMapper db;
    @Autowired WaitlistQuotaMapper quotas;
    @Autowired WaitlistOfferMapper offers;
    @Autowired WaitlistRedisTaskMapper taskMapper;
    @Autowired WaitlistRedisTaskProcessor processor;
    @Autowired WaitlistRedisService waitlistRedis;
    @MockitoBean ActivityBloomFilter bloom;
    // Keep this suite isolated from live Kafka consumers; exercise Redis and MySQL for real.
    @MockitoBean BookingDispatchService dispatch;
    @Autowired BookingSubmissionService submission;

    final String run = UUID.randomUUID().toString().substring(0,8);
    final List<Long> activities = new ArrayList<>();
    final List<Long> users = new ArrayList<>();
    final List<String> queueKeys = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    record Fixture(long activityId, String orderId) {}

    @BeforeAll
    void prepare() throws Exception
    {
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection())
        {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("sql/20260928_waitlist_complete.sql"));
        }
        for (int i=0;i<4;i++)
        {
            String no = "wl_" + run + "_" + i;
            jdbc.update("INSERT INTO campus_user(student_no,name,role,password_hash) VALUES(?,?,'STUDENT',?)", no, "Waitlist fixture", encoder.encode("FixtureTest123!"));
            users.add(jdbc.queryForObject("SELECT id FROM campus_user WHERE student_no=?",Long.class,no));
        }
    }

    void login(int index)
    {
        UserHolder.saveUser(new LoginUser(users.get(index), "fixture", "fixture", "STUDENT"));
    }

    Fixture fixture(int seats)
    {
        String title = "WL-" + run + "-" + activities.size();
        jdbc.update("INSERT INTO activity(title,location,start_time,end_time,registration_start_time,registration_end_time,total_quota,remaining_quota,status) VALUES(?,'fixture',DATE_ADD(NOW(),INTERVAL 2 HOUR),DATE_ADD(NOW(),INTERVAL 3 HOUR),DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),?,?,'PUBLISHED')",title,seats,seats);
        long id = jdbc.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,title);
        activities.add(id);
        assertTrue(bookingRedis.initialize(id,1,seats,Instant.now().minusSeconds(3600),Instant.now().plusSeconds(3600),Map.of()));
        String order = reserve(id,0,true);
        return new Fixture(id,order);
    }

    String reserve(long activityId, int user, boolean consume)
    {
        String id=UUID.randomUUID().toString();
        assertTrue(bookingRedis.reserve(activityId,users.get(user),id,1,Instant.now().plusSeconds(120)).success());
        if (consume)
        {
            var message=json.readValue(bookingRedis.findRequestJson(activityId,id),BookingMessage.class);
            var result=consumer.consume(message);
            assertEquals("SUCCEEDED",result.status());
            bookingRedis.markSucceeded(message,result.registrationId());
        }
        return id;
    }

    long join(Fixture f,int user)
    {
        login(user);
        return waiting.join(f.activityId()).getId();
    }

    WaitlistQuota cancelAndProgress(Fixture f)
    {
        login(0);
        cancel.cancelInDatabase(f.activityId(),f.orderId());
        sync.synchronize(f.orderId());
        return quotas.findBySourceOrderId(f.orderId());
    }

    WaitlistOffer current(Long quotaId)
    {
        return offers.findById(db.quota(quotaId).getCurrentOfferId());
    }

    void stock(Fixture f,int expected)
    {
        assertEquals(expected,jdbc.queryForObject("SELECT remaining_quota FROM activity WHERE id=?",Integer.class,f.activityId()));
        assertEquals(Integer.toString(expected),redis.opsForHash().get(RedisConstants.bookingInventoryKey(f.activityId()),"quota"));
    }

    @Test
    void noCandidateReturnsExactlyOnceAndOldTasksAreHarmless()
    {
        Fixture f=fixture(1);
        WaitlistQuota q=cancelAndProgress(f);
        assertEquals("RETURNED",db.quota(q.getId()).getStatus());
        stock(f,1);
        cancelAndProgress(f);
        var rows=jdbc.queryForList("SELECT id FROM waitlist_redis_task WHERE quota_id=? ORDER BY quota_version",Long.class,q.getId());
        for (Long id:rows)
        {
            var t=taskMapper.findById(id);
            assertTrue(processor.tryProcess(id));
            if ("HOLD".equals(t.getOperationType())) waitlistRedis.hold(id,q.getId(),json.readValue(t.getPayload(),WaitlistHoldPayload.class));
            else waitlistRedis.returnQuota(id,q.getId(),t.getQuotaVersion(),json.readValue(t.getPayload(),WaitlistReturnPayload.class));
        }
        stock(f,1);
    }

    @Test
    void fifoDeclineConfirmAndCancelAgain()
    {
        Fixture f=fixture(1);
        long first=join(f,1);
        assertEquals(first,waiting.join(f.activityId()).getId());
        waiting.cancelWaiting(f.activityId(),first);
        long second=join(f,2);
        long last=join(f,1);
        assertTrue(last>second);
        waiting.cancelWaiting(f.activityId(),first);
        assertEquals(last,waiting.findCurrent(f.activityId()).waitlistId());
        WaitlistQuota q=cancelAndProgress(f);
        WaitlistOffer b=current(q.getId());
        assertEquals(second,b.getWaitlistId());
        assertEquals("OFFERED",b.getStatus());
        stock(f,0);
        assertEquals("DUPLICATE",bookingRedis.reserve(f.activityId(),users.get(2),UUID.randomUUID().toString(),1,Instant.now().plusSeconds(120)).code());
        login(1);
        assertThrows(com.campus.ticket.exception.BusinessException.class,()->workflow.confirm(b.getId()));
        login(2);
        workflow.decline(b.getId());
        workflow.decline(b.getId());
        coordinator.progress(q.getId());
        WaitlistOffer c=current(q.getId());
        assertEquals(last,c.getWaitlistId());
        login(1);
        String order=workflow.confirm(c.getId());
        assertEquals(order,workflow.confirm(c.getId()));
        coordinator.progress(q.getId());
        assertEquals("CONSUMED",db.quota(q.getId()).getStatus());
        stock(f,0);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_notification WHERE activity_id=?",Integer.class,f.activityId()));
        cancel.cancelInDatabase(f.activityId(),order);
        sync.synchronize(order);
        stock(f,1);
        // 重放原链的所有 transition，不能清除新链状态或重复改变库存。
        for (var row:jdbc.queryForList("SELECT id FROM waitlist_redis_task WHERE quota_id=? AND operation_type IN ('OFFER','RELEASE','CONFIRM')",q.getId()))
        {
            var t=taskMapper.findById(((Number)row.get("id")).longValue());
            assertEquals("APPLIED",waitlistRedis.transition(t.getId(),t.getQuotaId(),t.getQuotaVersion(),t.getOperationType(),json.readValue(t.getPayload(),WaitlistTransitionPayload.class)));
        }
        stock(f,1);
    }

    @Test
    void delayedQueueAndDatabaseFallbackBothExpireOffers() throws Exception
    {
        Fixture f=fixture(1);
        join(f,1);
        join(f,2);
        WaitlistQuota q=cancelAndProgress(f);
        WaitlistOffer first=current(q.getId());
        jdbc.update("UPDATE waitlist_offer SET confirm_deadline=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",first.getId());
        String queue="campus:waitlist:{test-"+run+"}:delay";
        queueKeys.add(queue);
        WaitlistDelayService delays=new WaitlistDelayService(redisson,coordinator,offers);
        ReflectionTestUtils.setField(delays,"queueName",queue);
        delays.start();
        delays.schedule(new WaitlistOfferReadyEvent(first.getId(),LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusNanos(200_000_000)));
        await(()->{ delays.poll(); return !first.getId().equals(db.quota(q.getId()).getCurrentOfferId()); });
        assertEquals("EXPIRED",offers.findById(first.getId()).getStatus());
        WaitlistOffer next=current(q.getId());
        assertEquals("OFFERED",next.getStatus());
        // 不投递延迟消息，模拟消息遗漏；直接使用补偿流程检查数据库截止时间。
        jdbc.update("UPDATE waitlist_offer SET confirm_deadline=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",next.getId());
        coordinator.progress(q.getId());
        coordinator.progress(q.getId());
        assertEquals("EXPIRED",offers.findById(next.getId()).getStatus());
        stock(f,1);
    }

    @Test
    void redisSuccessBeforeDatabaseAcknowledgementIsReplayable()
    {
        Fixture f=fixture(1);
        join(f,1);
        login(0);
        cancel.cancelInDatabase(f.activityId(),f.orderId());
        WaitlistQuota q=quotas.findBySourceOrderId(f.orderId());
        var hold=taskMapper.findById(db.nextTask(q.getId()));
        waitlistRedis.hold(hold.getId(),q.getId(),json.readValue(hold.getPayload(),WaitlistHoldPayload.class));
        assertEquals("PENDING",taskMapper.findById(hold.getId()).getStatus());
        assertTrue(processor.tryProcess(hold.getId()));
        assertTrue(workflow.advance(q.getId()));
        WaitlistOffer preparing=current(q.getId());
        assertEquals("PREPARING",preparing.getStatus());
        login(1);
        assertThrows(com.campus.ticket.exception.BusinessException.class,()->workflow.confirm(preparing.getId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_notification WHERE offer_id=?",Integer.class,preparing.getId()));
        var task=taskMapper.findById(db.nextTask(q.getId()));
        var payload=json.readValue(task.getPayload(),WaitlistTransitionPayload.class);
        waitlistRedis.transition(task.getId(),task.getQuotaId(),task.getQuotaVersion(),task.getOperationType(),payload);
        coordinator.progress(q.getId());
        assertEquals("OFFERED",current(q.getId()).getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_notification WHERE offer_id=?",Integer.class,preparing.getId()));
        stock(f,0);
    }

    @Test
    void pendingOrdinaryBookingWinsRedisRaceAndCandidateIsSkipped()
    {
        Fixture f=fixture(2);
        reserve(f.activityId(),1,false);
        // 模拟候补校验快照之后，普通报名先完成预占、数据库候补后提交的交错。
        jdbc.update("INSERT INTO activity_waitlist(activity_id,user_id,status) VALUES(?,?,'WAITING')",f.activityId(),users.get(1));
        long valid=join(f,2);
        WaitlistQuota q=cancelAndProgress(f);
        assertEquals(valid,current(q.getId()).getWaitlistId());
        assertEquals("OFFERED",current(q.getId()).getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_offer WHERE quota_id=? AND close_reason='USER_ALREADY_OCCUPIED'",Integer.class,q.getId()));
        assertEquals("0",redis.opsForHash().get(RedisConstants.bookingInventoryKey(f.activityId()),"quota"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_notification WHERE activity_id=?",Integer.class,f.activityId()));
    }

    @Test
    void concurrentConfirmVersusDeclineHasOneOutcome() throws Exception
    {
        Fixture f=fixture(1);
        join(f,1);
        WaitlistQuota q=cancelAndProgress(f);
        Long offerId=current(q.getId()).getId();
        ExecutorService pool=Executors.newFixedThreadPool(8);
        CountDownLatch start=new CountDownLatch(1);
        try
        {
            List<Future<?>> futures=new ArrayList<>();
            for (int i=0;i<8;i++)
            {
                final boolean confirm=i%2==0;
                futures.add(pool.submit(()->{
                    login(1);
                    try
                    {
                        start.await();
                        if(confirm) workflow.confirm(offerId); else workflow.decline(offerId);
                    }
                    catch(com.campus.ticket.exception.BusinessException expected) {}
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    finally { UserHolder.removeUser(); }
                }));
            }
            start.countDown();
            for(Future<?> future:futures) future.get(20,TimeUnit.SECONDS);
        }
        finally { pool.shutdownNow(); }
        coordinator.progress(q.getId());
        String status=offers.findById(offerId).getStatus();
        assertTrue(Set.of("CONFIRMED","CANCELLED").contains(status));
        stock(f,"CONFIRMED".equals(status)?0:1);
        assertEquals("CONFIRMED".equals(status)?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_key=?",Integer.class,"waitlist:"+offerId));
    }

    @Test
    void expiredConfirmationRejectedAndActivityClosureStopsPromotion()
    {
        Fixture f=fixture(1);
        join(f,1);
        join(f,2);
        WaitlistQuota q=cancelAndProgress(f);
        Long offerId=current(q.getId()).getId();
        jdbc.update("UPDATE waitlist_offer SET confirm_deadline=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",offerId);
        login(1);
        assertThrows(com.campus.ticket.exception.BusinessException.class,()->workflow.confirm(offerId));
        jdbc.update("UPDATE activity SET registration_end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",f.activityId());
        coordinator.progress(q.getId());
        stock(f,1);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM activity_waitlist WHERE activity_id=? AND active_flag=1",Integer.class,f.activityId()));
    }

    @Test
    void httpPermissionsNotificationsAndConfirmation() throws Exception
    {
        Fixture f=fixture(1);
        join(f,1);
        WaitlistQuota q=cancelAndProgress(f);
        Long id=current(q.getId()).getId();
        var login1=http("POST","/auth/login",null,Map.of("studentNo","wl_"+run+"_1","password","FixtureTest123!"));
        assertEquals(200,login1.statusCode());
        String token1=json.readTree(login1.body()).path("token").asText();
        tokens.add(token1);
        var login2=http("POST","/auth/login",null,Map.of("studentNo","wl_"+run+"_2","password","FixtureTest123!"));
        assertEquals(200,login2.statusCode());
        String token2=json.readTree(login2.body()).path("token").asText();
        tokens.add(token2);
        assertEquals(401,http("GET","/waitlist-offers/"+id,null,null).statusCode());
        assertEquals(404,http("GET","/waitlist-offers/"+id,token2,null).statusCode());
        assertEquals(200,http("GET","/waitlist-offers/"+id,token1,null).statusCode());
        var notices=http("GET","/users/me/waitlist-notifications",token1,null);
        assertEquals(200,notices.statusCode());
        long noticeId=json.readTree(notices.body()).get(0).path("id").asLong();
        assertEquals(404,http("POST","/users/me/waitlist-notifications/"+noticeId+"/read",token2,null).statusCode());
        assertEquals(200,http("POST","/users/me/waitlist-notifications/"+noticeId+"/read",token1,null).statusCode());
        var first=http("POST","/waitlist-offers/"+id+"/confirm",token1,null);
        var second=http("POST","/waitlist-offers/"+id+"/confirm",token1,null);
        assertEquals(200,first.statusCode(),first.body());
        assertEquals(200,second.statusCode(),second.body());
        assertEquals(json.readTree(first.body()).path("orderId").asText(),json.readTree(second.body()).path("orderId").asText());
        stock(f,0);
    }

    @Test
    void redisFailureRetainsTaskAndRecoversWithoutUserResubmission()
    {
        Fixture f=fixture(1);
        login(0);
        cancel.cancelInDatabase(f.activityId(),f.orderId());
        WaitlistQuota q=quotas.findBySourceOrderId(f.orderId());
        Long task=db.nextTask(q.getId());
        String key=RedisConstants.bookingInventoryKey(f.activityId());
        redis.opsForHash().put(key,"epoch","2");
        assertThrows(IllegalStateException.class,()->processor.tryProcess(task));
        assertEquals("PENDING",taskMapper.findById(task).getStatus());
        stock(f,0);
        redis.opsForHash().put(key,"epoch","1");
        jdbc.update("UPDATE waitlist_redis_task SET next_attempt_time=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",task);
        coordinator.progress(q.getId());
        assertEquals("DONE",taskMapper.findById(task).getStatus());
        stock(f,1);
    }

    @Test
    void databaseCancellationRollsBackWhenQuotaCreationFails()
    {
        Fixture f=fixture(1);
        jdbc.update("INSERT INTO waitlist_quota(activity_id,source_order_id) VALUES(?,?)",f.activityId(),f.orderId());
        login(0);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->cancel.cancelInDatabase(f.activityId(),f.orderId()));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM booking_order WHERE order_id=?",String.class,f.orderId()));
        assertEquals("REGISTERED",jdbc.queryForObject("SELECT status FROM registration WHERE activity_id=? AND user_id=?",String.class,f.activityId(),users.get(0)));
        stock(f,0);
    }

    String tokenFor(int index) throws Exception
    {
        var response = http("POST", "/auth/login", null, Map.of("studentNo", "wl_" + run + "_" + index, "password", "FixtureTest123!"));
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("token").asText();
        tokens.add(token);
        return token;
    }

    @Test
    void autoWaitlistAvailableSeatUsesNormalBooking() throws Exception
    {
        Fixture f = fixture(2);
        var response = http("POST", "/activities/" + f.activityId() + "/registrations", tokenFor(1), Map.of("joinWaitlistIfFull", true));
        assertEquals(202, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals("BOOKING", body.path("type").asText());
        assertEquals("PENDING", body.path("status").asText());
        String orderId = body.path("orderId").asText();
        assertFalse(orderId.isBlank());
        assertEquals(orderId, redis.opsForHash().get(RedisConstants.bookingInventoryKey(f.activityId()), "u:" + users.get(1)));
        assertEquals("0", redis.opsForHash().get(RedisConstants.bookingInventoryKey(f.activityId()), "quota"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM activity_waitlist WHERE activity_id=?", Integer.class, f.activityId()));
        org.mockito.Mockito.verify(dispatch).dispatchImmediately(f.activityId(), orderId);

        // Verify the accepted request can still complete via the existing consumer logic.
        var message = json.readValue(bookingRedis.findRequestJson(f.activityId(), orderId), BookingMessage.class);
        var consumed = consumer.consume(message);
        assertEquals("SUCCEEDED", consumed.status());
        bookingRedis.markSucceeded(message, consumed.registrationId());
        stock(f, 0);
    }

    @Test
    void soldOutAutoWaitlistCanReceiveAndConfirmInvitation() throws Exception
    {
        Fixture f = fixture(1);
        String token = tokenFor(2);
        String path = "/activities/" + f.activityId() + "/registrations";
        var first = http("POST", path, token, Map.of("joinWaitlistIfFull", true));
        assertEquals(200, first.statusCode(), first.body());
        var body = json.readTree(first.body());
        assertEquals("WAITLIST", body.path("type").asText());
        assertEquals("WAITING", body.path("status").asText());
        long waitlistId = body.path("waitlistId").asLong();
        assertTrue(waitlistId > 0);
        var repeat = http("POST", path, token, Map.of("joinWaitlistIfFull", true));
        assertEquals(200, repeat.statusCode(), repeat.body());
        assertEquals(waitlistId, json.readTree(repeat.body()).path("waitlistId").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM activity_waitlist WHERE activity_id=? AND user_id=?", Integer.class, f.activityId(), users.get(2)));
        assertNull(redis.opsForHash().get(RedisConstants.bookingInventoryKey(f.activityId()), "u:" + users.get(2)));
        org.mockito.Mockito.verifyNoInteractions(dispatch);

        WaitlistQuota q = cancelAndProgress(f);
        WaitlistOffer offer = current(q.getId());
        assertEquals("OFFERED", offer.getStatus());
        var confirmed = http("POST", "/waitlist-offers/" + offer.getId() + "/confirm", token, null);
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        assertEquals("SUCCEEDED", json.readTree(confirmed.body()).path("status").asText());
        assertEquals("CONFIRMED", offers.findById(offer.getId()).getStatus());
        assertEquals("CONSUMED", db.quota(q.getId()).getStatus());
        stock(f, 0);
    }

    @Test
    void soldOutWithoutOptInPreservesOldApiBehavior() throws Exception
    {
        Fixture f = fixture(1);
        String token = tokenFor(3);
        String path = "/activities/" + f.activityId() + "/registrations";
        for (Object body : Arrays.asList(null, Map.of("joinWaitlistIfFull", false)))
        {
            var response = http("POST", path, token, body);
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("QUOTA_EXHAUSTED", json.readTree(response.body()).path("code").asText());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM activity_waitlist WHERE activity_id=?", Integer.class, f.activityId()));
        org.mockito.Mockito.verifyNoInteractions(dispatch);
        stock(f, 0);
    }

    @Test
    void concurrentSoldOutSubmissionsCreateOneWaitlistRecord() throws Exception
    {
        Fixture f = fixture(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try
        {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++)
            {
                calls.add(() ->
                {
                    login(1);
                    try
                    {
                        var result = submission.submit(f.activityId(), true);
                        assertEquals("WAITLIST", result.type());
                        return result.waitlistId();
                    }
                    finally { UserHolder.removeUser(); }
                });
            }
            Set<Long> ids = new HashSet<>();
            for (Future<Long> future : pool.invokeAll(calls, 20, TimeUnit.SECONDS)) ids.add(future.get());
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM activity_waitlist WHERE activity_id=? AND user_id=?", Integer.class, f.activityId(), users.get(1)));
            stock(f, 0);
        }
        finally { pool.shutdownNow(); }
    }

    java.net.http.HttpResponse<String> http(String method,String path,String token,Object body) throws Exception
    {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return java.net.http.HttpClient.newHttpClient().send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    void await(BooleanSupplier condition) throws Exception
    {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) { if(condition.getAsBoolean())return; Thread.sleep(50); }
        fail("Delayed queue did not deliver");
    }

    @AfterEach void clearLogin(){ UserHolder.removeUser(); }

    @AfterAll
    void cleanup()
    {
        UserHolder.removeUser();
        for(Long id:activities)
        {
            var quotaIds=jdbc.queryForList("SELECT id FROM waitlist_quota WHERE activity_id=?",Long.class,id);
            for(Long q:quotaIds)
            {
                jdbc.update("DELETE FROM waitlist_redis_task WHERE quota_id=?",q);
                redis.delete(RedisConstants.waitlistQuotaKey(id,q));
            }
            jdbc.update("DELETE FROM waitlist_notification WHERE activity_id=?",id);
            jdbc.update("DELETE o FROM waitlist_offer o JOIN activity_waitlist w ON w.id=o.waitlist_id WHERE w.activity_id=?",id);
            jdbc.update("DELETE FROM waitlist_quota WHERE activity_id=?",id);
            jdbc.update("DELETE FROM activity_waitlist WHERE activity_id=?",id);
            jdbc.update("DELETE l FROM booking_order_log l JOIN booking_order o ON o.order_id=l.order_id WHERE o.activity_id=?",id);
            jdbc.update("DELETE FROM booking_order WHERE activity_id=?",id);
            jdbc.update("DELETE FROM registration WHERE activity_id=?",id);
            jdbc.update("DELETE FROM booking_inventory WHERE activity_id=?",id);
            jdbc.update("DELETE FROM activity WHERE id=?",id);
            redis.delete(List.of(RedisConstants.bookingInventoryKey(id),RedisConstants.bookingRequestsKey(id),RedisConstants.bookingPendingKey(id),RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX+id,RedisConstants.ACTIVITY_CACHE_VERSION_KEY_PREFIX+id));
        }
        for(String key:queueKeys)
        {
            var ready=redisson.<String>getBlockingQueue(key,StringCodec.INSTANCE);
            var delayed=redisson.getDelayedQueue(ready);
            delayed.delete();
            delayed.destroy();
            ready.delete();
        }
        for(String token:tokens) redis.delete(RedisConstants.LOGIN_KEY_PREFIX+token);
        for(Long user:users)
        {
            redis.delete(RedisConstants.RATE_LIMIT_SLIDING_KEY_PREFIX + "user:" + user + ":booking-submit");
            jdbc.update("DELETE FROM campus_user WHERE id=?",user);
        }
        System.out.println("WAITLIST_IT fixtures cleaned: "+run);
    }
}

