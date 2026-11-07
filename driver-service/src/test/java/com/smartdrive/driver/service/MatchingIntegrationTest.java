package com.smartdrive.driver.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.event.OrderCreatedEvent;
import com.smartdrive.driver.config.MatchingRabbitTopology;
import com.smartdrive.driver.config.MatchingRetryConfiguration;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.mapper.DriverProfileMapper;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_REDIS_INTEGRATION", matches = "true")
class MatchingIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "SMARTDRIVE_RABBITMQ_INTEGRATION", matches = "true")
    void transientFailureBacksOffAndCommitsOnceWithRealBroker() throws Exception {
        try (var f = new Fixture(); var broker = new Broker()) {
            f.online(0, "20.0001");
            var matching = spy(f.matching);
            var event = f.event();
            doThrow(new IllegalStateException("injected outage"))
                    .doThrow(new IllegalStateException("injected outage"))
                    .doCallRealMethod().when(matching).match(event);
            var listener = new OrderCreatedListener(f.json, matching,
                    new MatchingRetryConfiguration().matchingRetryTemplate(4, 1000, 5000));
            var container = consumer(broker, listener);
            try {
                long start = System.nanoTime();
                broker.send(f.json.writeValueAsBytes(event), event.eventId());
                await(() -> f.count(event.orderId()) == 1);
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 2900,
                        "Two failures must wait the 1s + 2s backoff");
                verify(matching, times(3)).match(event);
                assertNull(broker.rabbit.receive("driver.matching.dlq", 200));
                assertEquals(event.eventId(), f.notices.processedEvent(event.orderId()));
            } finally { container.stop(); }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SMARTDRIVE_RABBITMQ_INTEGRATION", matches = "true")
    void exhaustionDeadLettersLocatableOriginalAndReplayAfterRepairRemainsIdempotent() throws Exception {
        try (var f = new Fixture(); var broker = new Broker()) {
            f.online(0, "20.0001");
            var matching = spy(f.matching);
            var event = f.event();
            doThrow(new IllegalStateException("injected persistent outage")).when(matching).match(event);
            var listener = new OrderCreatedListener(f.json, matching,
                    new MatchingRetryConfiguration().matchingRetryTemplate(4, 1000, 5000));
            var container = consumer(broker, listener);
            try {
                long start = System.nanoTime();
                byte[] original = f.json.writeValueAsBytes(event);
                broker.send(original, event.eventId());
                var dead = broker.rabbit.receive("driver.matching.dlq", 12000);
                assertNotNull(dead);
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 6900,
                        "Exhaustion must wait the 1s + 2s + 4s backoff");
                verify(matching, times(4)).match(event);
                assertEquals(0, f.count(event.orderId()));
                assertNull(f.notices.processedEvent(event.orderId()));
                assertArrayEquals(original, dead.getBody());
                assertEquals(event.eventId(), dead.getMessageProperties().getMessageId());
                var death = (java.util.Map<?, ?>) ((List<?>) dead.getMessageProperties().getHeaders().get("x-death")).getFirst();
                assertEquals("driver.matching.q", death.get("queue").toString());
                assertEquals("rejected", death.get("reason").toString());
                assertEquals(1L, ((Number) death.get("count")).longValue());
                doCallRealMethod().when(matching).match(event);
                broker.send(dead.getBody(), event.eventId());
                await(() -> f.count(event.orderId()) == 1);
                broker.send(dead.getBody(), event.eventId());
                await(() -> mockingDetails(matching).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("match")).count() >= 6);
                assertEquals(1, f.count(event.orderId()));
                assertEquals(event.eventId(), f.notices.processedEvent(event.orderId()));
                assertNull(broker.rabbit.receive("driver.matching.dlq", 200));
            } finally { container.stop(); }
        }
    }

    private static SimpleMessageListenerContainer consumer(Broker broker, OrderCreatedListener listener) {
        var container = new SimpleMessageListenerContainer(broker.connection);
        container.setQueueNames("driver.matching.q");
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setMessageListener((ChannelAwareMessageListener) listener::receive);
        container.start();
        return container;
    }

    @Test void existingNoticeStatusIsPreservedAndListIsScopedOrderedAndLimited() throws Exception {
        try (var f = new Fixture()) {
            f.online(0, "20.0001");
            f.notices.insertNotice(f.base, f.base);
            f.jdbc.update("UPDATE order_notice SET status='READ' WHERE order_id=? AND driver_id=?", f.base, f.base);
            f.matching.match(f.event());
            assertEquals(1, f.count(f.base));
            assertTrue(f.notices.findNew(f.base, 100).isEmpty());
            assertTrue(f.notices.wasNotified(f.base, f.base));
            f.notices.insertNotice(f.base + 10, f.base);
            f.notices.insertNotice(f.base + 11, f.base + 1);
            f.notices.insertNotice(f.base + 11, f.base);
            assertEquals(List.of(f.base + 11, f.base + 10), f.notices.findNew(f.base, 100));
            assertEquals(List.of(f.base + 11), f.notices.findNew(f.base, 1));
            assertEquals(List.of(f.base + 11), f.notices.findNew(f.base + 1, 100));
        }
    }

    @Test void concurrentRedeliveryMatchesOnceAndKeepsOriginalCohort() throws Exception {
        try (var f = new Fixture()) {
            f.online(0, "20.0001");
            f.online(1, "20.0002");
            f.online(2, "21"); // far away
            f.online(3, "20");
            f.redis.delete(DriverService.ONLINE_PREFIX + (f.base + 3)); // stale GEO entry
            var event = f.event();
            try (var executor = Executors.newFixedThreadPool(10)) {
                var results = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 10; i++) results.add(executor.submit(() -> f.matching.match(event)));
                for (var result : results) result.get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(2, f.count(event.orderId()));
            assertEquals(List.of(event.orderId()), f.notices.findNew(f.base, 100));
            assertTrue(f.notices.findNew(f.base + 2, 100).isEmpty());
            assertTrue(f.notices.findNew(f.base + 3, 100).isEmpty());
            f.jdbc.update("UPDATE order_notice SET status='READ' WHERE order_id=? AND driver_id=?", event.orderId(), f.base);
            f.online(4, "20.0003");
            f.matching.match(event);
            assertEquals(2, f.count(event.orderId()));
            assertTrue(f.notices.findNew(f.base, 100).isEmpty()); // no status reset
            assertFalse(f.notices.wasNotified(event.orderId(), f.base + 4));
        }
    }

    @Test void partialDatabaseFailureRollsBackAllNoticesAndClaimThenRetrySucceeds() throws Exception {
        try (var f = new Fixture()) {
            f.online(0, "20.0001");
            f.online(1, "20.0002");
            var mapper = spy(f.notices);
            doThrow(new IllegalStateException("injected second insert failure")).when(mapper).insertNotice(f.base, f.base + 1);
            var failed = new OrderMatchingService(mapper, f.drivers, f.manager);
            var event = f.event();
            assertThrows(IllegalStateException.class, () -> failed.match(event));
            assertEquals(0, f.count(event.orderId()));
            assertNull(f.notices.processedEvent(event.orderId()));
            f.matching.match(event);
            assertEquals(2, f.count(event.orderId()));
            assertEquals(event.eventId(), f.notices.processedEvent(event.orderId()));
        }
    }

    @Test void emptyMatchIsCommittedAndRedisFailureLeavesNoClaim() throws Exception {
        try (var f = new Fixture()) {
            var unavailable = mock(DriverService.class);
            when(unavailable.nearby(any())).thenThrow(new IllegalStateException("Redis unavailable"));
            var event = f.event();
            assertThrows(IllegalStateException.class,
                    () -> new OrderMatchingService(f.notices, unavailable, f.manager).match(event));
            assertNull(f.notices.processedEvent(event.orderId()));
            f.matching.match(event);
            assertEquals(event.eventId(), f.notices.processedEvent(event.orderId()));
            assertEquals(0, f.count(event.orderId()));
            f.online(0, "20.0001");
            f.matching.match(event);
            assertEquals(0, f.count(event.orderId()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SMARTDRIVE_RABBITMQ_INTEGRATION", matches = "true")
    void realBrokerRedeliveryCommitsOnceAndInvalidJsonEntersDlq() throws Exception {
        try (var f = new Fixture(); var broker = new Broker()) {
            f.online(0, "20.0001");
            f.online(1, "20.0002");
            var listener = new OrderCreatedListener(f.json, f.matching,
                    new MatchingRetryConfiguration().matchingRetryTemplate(4, 1, 4));
            var received = new java.util.concurrent.atomic.AtomicInteger();
            var container = new SimpleMessageListenerContainer(broker.connection);
            container.setQueueNames("driver.matching.q");
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setConcurrentConsumers(2);
            container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                listener.receive(message, channel);
                received.incrementAndGet();
            });
            container.start();
            try {
                var event = f.event();
                for (int i = 0; i < 10; i++) broker.send(f.json.writeValueAsBytes(event), event.eventId());
                await(() -> received.get() == 10 && f.count(event.orderId()) == 2);
                assertEquals(event.eventId(), f.notices.processedEvent(event.orderId()));
                f.online(4, "20.0003");
                broker.send(f.json.writeValueAsBytes(event), event.eventId());
                // A FIFO invalid message proves this delivery has also reached the listener.
                broker.send("invalid-json".getBytes(StandardCharsets.UTF_8), "invalid");
                var dead = broker.rabbit.receive("driver.matching.dlq", 5000);
                assertNotNull(dead);
                assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
                await(() -> received.get() == 12);
                assertEquals(2, f.count(event.orderId()));
                assertFalse(f.notices.wasNotified(event.orderId(), f.base + 4));
            } finally { container.stop(); }
        }
    }

    interface CheckedCondition { boolean satisfied() throws Exception; }
    static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.satisfied()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for broker/database convergence");
    }

    static class Fixture implements AutoCloseable {
        final long base = ThreadLocalRandom.current().nextLong(100_000_000_000L, 900_000_000_000L);
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv().getOrDefault("MYSQL_DRIVER_URL",
                "jdbc:mysql://localhost:3306/driver_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"),
                System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"), System.getenv("SMARTDRIVE_DB_PASSWORD"));
        final JdbcTemplate jdbc = new JdbcTemplate(source);
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        final LettuceConnectionFactory redisFactory;
        final StringRedisTemplate redis;
        final DriverService drivers;
        final OrderNoticeMapper notices;
        final OrderMatchingService matching;

        Fixture() throws Exception {
            var config = new org.apache.ibatis.session.Configuration();
            config.addMapper(OrderNoticeMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            notices = new SqlSessionTemplate(factory.getObject()).getMapper(OrderNoticeMapper.class);
            var redisConfig = new RedisStandaloneConfiguration(System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
            redisConfig.setPassword(System.getenv("REDIS_PASSWORD"));
            redisFactory = new LettuceConnectionFactory(redisConfig);
            redisFactory.afterPropertiesSet();
            redis = new StringRedisTemplate(redisFactory);
            redis.afterPropertiesSet();
            drivers = new DriverService(mock(DriverProfileMapper.class), redis);
            matching = new OrderMatchingService(notices, drivers, manager);
        }

        void online(int offset, String longitude) {
            long id = base + offset;
            // Unique synthetic IDs and remote test coordinates; do not touch another test's GEO members.
            redis.opsForGeo().add(DriverService.GEO_KEY, new org.springframework.data.geo.Point(Double.parseDouble(longitude), 20), "" + id);
            redis.opsForValue().set(DriverService.ONLINE_PREFIX + id, "1", Duration.ofSeconds(120));
        }
        OrderCreatedEvent event() {
            return new OrderCreatedEvent(UUID.randomUUID().toString(), "order.created", base,
                    new BigDecimal("20"), new BigDecimal("20"), Instant.now());
        }
        int count(long orderId) { return jdbc.queryForObject("SELECT COUNT(*) FROM order_notice WHERE order_id=?", Integer.class, orderId); }

        @Override public void close() {
            try {
                jdbc.update("DELETE FROM order_notice WHERE order_id IN (?,?,?)", base, base + 10, base + 11);
                jdbc.update("DELETE FROM processed_order_event WHERE order_id=?", base);
            } finally {
                try { for (int i = 0; i < 5; i++) drivers.offline(base + i); }
                finally { redisFactory.destroy(); }
            }
        }
    }

    static class Broker implements AutoCloseable {
        final String vhost = "smartdrive-step13-" + UUID.randomUUID();
        final String user = System.getenv().getOrDefault("RABBITMQ_USER", "guest");
        final String password = System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest");
        final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        final CachingConnectionFactory connection;
        final RabbitTemplate rabbit;
        final ObjectMapper json = new ObjectMapper();
        Broker() throws Exception {
            connection = new CachingConnectionFactory(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
            connection.setUsername(user);
            connection.setPassword(password);
            connection.setVirtualHost(vhost);
            connection.setConnectionTimeout(3000);
            rabbit = new RabbitTemplate(connection);
            try {
                request("PUT", "/api/vhosts/" + vhost, "{}");
                request("PUT", "/api/permissions/" + vhost + "/" + URLEncoder.encode(user, StandardCharsets.UTF_8),
                        "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
                var admin = new RabbitAdmin(connection);
                var resources = new MatchingRabbitTopology().matchingTopology().getDeclarables();
                for (var resource : resources) if (resource instanceof Exchange e) admin.declareExchange(e);
                for (var resource : resources) if (resource instanceof Queue q) admin.declareQueue(q);
                for (var resource : resources) if (resource instanceof Binding b) admin.declareBinding(b);
            } catch (Exception failure) { close(); throw failure; }
        }
        void send(byte[] body, String id) {
            var p = new MessageProperties();
            p.setContentType("application/json");
            p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            p.setMessageId(id);
            rabbit.send("ride.events", "order.created", new Message(body, p));
        }
        String request(String method, String path, String body) throws Exception {
            String basic = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            var req = HttpRequest.newBuilder(URI.create(System.getenv().getOrDefault("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672") + path))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2 && !(method.equals("DELETE") && response.statusCode() == 404))
                throw new IllegalStateException("RabbitMQ fixture HTTP " + response.statusCode());
            return response.body();
        }
        @Override public void close() throws Exception {
            try { connection.destroy(); }
            finally {
                try { request("DELETE", "/api/vhosts/" + vhost, null); }
                finally { http.close(); }
            }
        }
    }
}
