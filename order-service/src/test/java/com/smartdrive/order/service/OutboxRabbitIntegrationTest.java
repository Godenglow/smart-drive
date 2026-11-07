package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;

import com.smartdrive.order.config.RabbitTopology;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Real MySQL + RabbitMQ, production topology in a randomly named, disposable test vhost. */
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_RABBITMQ_INTEGRATION", matches = "true")
class OutboxRabbitIntegrationTest {
    @Test void realBrokerReceivesPersistentWireEventAndRejectRoutesToDlq() throws Exception {
        try (var f = new OutboxTestFixture(); var broker = new Broker(f)) {
            long orderId = f.create().id();
            long id = f.eventId(orderId);
            var expected = f.event(id);
            new OutboxPublisher(f.outbox, broker.sender(), f.manager, 50).publishOne(id);
            assertEquals("SENT", f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
            broker.rabbit.execute(channel -> {
                var delivery = channel.basicGet(RabbitTopology.MATCHING_QUEUE, false);
                assertNotNull(delivery);
                assertEquals(2, delivery.getProps().getDeliveryMode());
                assertEquals("application/json", delivery.getProps().getContentType());
                assertEquals(expected.eventId, delivery.getProps().getMessageId());
                var payload = f.json.readTree(delivery.getBody());
                assertEquals(expected.eventId, payload.get("eventId").asText());
                assertEquals(orderId, payload.get("orderId").asLong());
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                return null;
            });
            var dead = broker.rabbit.receive(RabbitTopology.DEAD_QUEUE, 5000);
            assertNotNull(dead);
            assertEquals(expected.eventId, dead.getMessageProperties().getMessageId());
            assertEquals(MessageDeliveryMode.PERSISTENT, dead.getMessageProperties().getReceivedDeliveryMode());
            assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
        }
    }

    @Test void realConnectionOutageAndUnroutableAckRemainNewUntilRecovery() throws Exception {
        try (var f = new OutboxTestFixture(); var broker = new Broker(f)) {
            long id = f.eventId(f.create().id());
            String eventId = f.event(id).eventId;
            int closedPort;
            try (var reserved = new ServerSocket(0)) { closedPort = reserved.getLocalPort(); }
            var unavailable = new CachingConnectionFactory("127.0.0.1", closedPort);
            unavailable.setConnectionTimeout(500);
            try {
                var template = new RabbitTemplate(unavailable);
                template.setMandatory(true);
                var disconnected = new OrderEventSender(template, 500);
                new OutboxPublisher(f.outbox, disconnected, f.manager, 50).publishOne(id);
                assertEquals("NEW", f.event(id).status);
                assertEquals(1, f.event(id).attempts);
            } finally { unavailable.destroy(); }

            // The exchange exists, but no route: broker ACK alone must not mark SENT.
            var binding = BindingBuilder.bind(new Queue(RabbitTopology.MATCHING_QUEUE))
                    .to(new TopicExchange(RabbitTopology.EVENTS)).with("order.created");
            broker.admin.removeBinding(binding);
            var publisher = new OutboxPublisher(f.outbox, broker.sender(), f.manager, 50);
            publisher.publishOne(id);
            assertEquals("NEW", f.event(id).status);
            assertEquals(2, f.event(id).attempts);
            assertNull(broker.rabbit.receive(RabbitTopology.MATCHING_QUEUE));

            broker.admin.declareBinding(binding);
            publisher.publishOne(id);
            assertEquals("SENT", f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
            assertEquals(3, f.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id));
            var received = broker.rabbit.receive(RabbitTopology.MATCHING_QUEUE, 5000);
            assertNotNull(received);
            assertEquals(eventId, received.getMessageProperties().getMessageId());
            publisher.publishOne(id);
            assertNull(broker.rabbit.receive(RabbitTopology.MATCHING_QUEUE));
        }
    }

    static class Broker implements AutoCloseable {
        final String vhost = "smartdrive-step12-" + UUID.randomUUID();
        final String user = System.getenv().getOrDefault("RABBITMQ_USER", "guest");
        final String password = System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest");
        final String management = System.getenv().getOrDefault("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672");
        final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        final CachingConnectionFactory connection;
        final RabbitTemplate rabbit;
        final RabbitAdmin admin;

        Broker(OutboxTestFixture f) throws Exception {
            connection = new CachingConnectionFactory(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
            connection.setUsername(user);
            connection.setPassword(password);
            connection.setVirtualHost(vhost);
            connection.setConnectionTimeout(3000);
            connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            connection.setPublisherReturns(true);
            rabbit = new RabbitTemplate(connection);
            rabbit.setMandatory(true);
            admin = new RabbitAdmin(connection);
            try {
                request("PUT", "/api/vhosts/" + vhost, "{}");
                request("PUT", "/api/permissions/" + vhost + "/" + URLEncoder.encode(user, StandardCharsets.UTF_8),
                        "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
                var resources = new RabbitTopology().orderEventTopology().getDeclarables();
                for (var resource : resources) if (resource instanceof Exchange exchange) admin.declareExchange(exchange);
                for (var resource : resources) if (resource instanceof Queue queue) admin.declareQueue(queue);
                for (var resource : resources) if (resource instanceof Binding binding) admin.declareBinding(binding);
                assertNotNull(admin.getQueueProperties(RabbitTopology.MATCHING_QUEUE));
                assertNotNull(admin.getQueueProperties(RabbitTopology.DEAD_QUEUE));
                var details = f.json.readTree(request("GET", "/api/queues/" + vhost + "/" + RabbitTopology.MATCHING_QUEUE, null));
                assertTrue(details.get("durable").asBoolean());
                assertEquals(RabbitTopology.DEAD_EXCHANGE, details.path("arguments").path("x-dead-letter-exchange").asText());
                assertTrue(f.json.readTree(request("GET", "/api/exchanges/" + vhost + "/" + RabbitTopology.EVENTS, null)).get("durable").asBoolean());
                assertTrue(f.json.readTree(request("GET", "/api/queues/" + vhost + "/" + RabbitTopology.DEAD_QUEUE, null)).get("durable").asBoolean());
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        OrderEventSender sender() { return new OrderEventSender(rabbit, 3000); }

        String request(String method, String path, String body) throws Exception {
            String basic = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create(management + path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2 && !(method.equals("DELETE") && response.statusCode() == 404)) {
                throw new IllegalStateException("RabbitMQ test setup HTTP " + response.statusCode());
            }
            return response.body();
        }

        @Override public void close() throws Exception {
            try { connection.destroy(); }
            finally {
                // Only this fixture's UUID vhost is removed, never a shared/default vhost.
                assertTrue(vhost.startsWith("smartdrive-step12-"));
                try { request("DELETE", "/api/vhosts/" + vhost, null); }
                finally { http.close(); }
            }
        }
    }
}
