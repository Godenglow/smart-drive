package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.Place;
import com.smartdrive.order.entity.OrderStatus;
import com.smartdrive.order.mapper.OrderMapper;
import com.smartdrive.order.mapper.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
class OrderMySqlIntegrationTest {
    @Test void concurrentRetriesAndCancellationPreserveDatabaseInvariants() throws Exception {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                System.getenv().getOrDefault("MYSQL_ORDER_URL", "jdbc:mysql://localhost:3306/order_db"),
                System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"),
                System.getenv("SMARTDRIVE_DB_PASSWORD"));
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OrderMapper.class);
        configuration.addMapper(OutboxMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setConfiguration(configuration);
        OrderMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderMapper.class);
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        NearbyDriverClient nearby = mock(NearbyDriverClient.class);
        when(nearby.hasNearby(any(), any())).thenReturn(true);
        OutboxMapper outbox = new SqlSessionTemplate(factory.getObject()).getMapper(OutboxMapper.class);
        OrderService service = new OrderService(mapper, nearby, new OrderCreationTransaction(mapper, outbox,
                new ObjectMapper().findAndRegisterModules(), new DataSourceTransactionManager(datasource)));
        long passengerId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        Principal passenger = new Principal(passengerId, "PASSENGER");
        Place place = new Place("Day9 集成测试", new BigDecimal("117.215"), new BigDecimal("39.136"));
        CreateOrderRequest request = new CreateOrderRequest(UUID.randomUUID(), place, place);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            // Hold every caller at precheck so all attempt insertion, exercising duplicate-key recovery.
            CyclicBarrier inserts = new CyclicBarrier(12);
            when(nearby.hasNearby(any(), any())).thenAnswer(call -> {
                inserts.await(20, TimeUnit.SECONDS);
                return true;
            });
            List<Future<Long>> creates = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                creates.add(pool.submit(() -> service.create(passenger, request).id()));
            }
            long id = creates.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<Long> create : creates) assertEquals(id, create.get(30, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ride_order WHERE passenger_id = ? AND request_id = ?",
                    Long.class, passengerId, request.requestId().toString()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'order.created'",
                    Long.class, id));
            reset(nearby);
            assertEquals(id, service.create(passenger, request).id());
            verifyNoInteractions(nearby);

            assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
                    () -> service.cancel(new Principal(passengerId + 1, "PASSENGER"), id)).errorCode());
            assertEquals("WAITING", mapper.findById(id).status);

            List<Future<Boolean>> cancels = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                cancels.add(pool.submit(() -> {
                    try {
                        service.cancel(passenger, id);
                        return true;
                    } catch (ApiException ex) {
                        assertEquals(ErrorCode.ORDER_NOT_WAITING, ex.errorCode());
                        return false;
                    }
                }));
            }
            int successes = 0;
            for (Future<Boolean> cancel : cancels) if (cancel.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals("CANCELLED", service.create(passenger, request).status());
            verifyNoInteractions(nearby);

            for (OrderStatus status : OrderStatus.values()) {
                if (status == OrderStatus.WAITING) continue;
                jdbc.update("UPDATE ride_order SET status = ? WHERE id = ?", status.name(), id);
                var before = mapper.findById(id);
                assertEquals(ErrorCode.ORDER_NOT_WAITING, assertThrows(ApiException.class,
                        () -> service.cancel(passenger, id)).errorCode());
                assertEquals(0, mapper.cancelWaiting(id, passengerId));
                var after = mapper.findById(id);
                assertEquals(before.status, after.status);
                assertEquals(before.updatedAt, after.updatedAt);
            }

            // The same UUID belongs to a separate idempotency scope for another passenger.
            when(nearby.hasNearby(any(), any())).thenReturn(true);
            long other = service.create(new Principal(passengerId + 1, "PASSENGER"), request).id();
            assertNotEquals(id, other);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            // Delete only the rows identified by this run's random passenger IDs and UUID.
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT id FROM ride_order WHERE passenger_id IN (?, ?) AND request_id = ?)",
                    passengerId, passengerId + 1, request.requestId().toString());
            jdbc.update("DELETE FROM ride_order WHERE passenger_id IN (?, ?) AND request_id = ?",
                    passengerId, passengerId + 1, request.requestId().toString());
        }
    }
}
