package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.mapper.OrderMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real InnoDB, MyBatis and Spring transaction proxy; only the remote eligibility call is substituted. */
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
class OrderAcceptanceMySqlIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv().getOrDefault("MYSQL_ORDER_URL",
                    "jdbc:mysql://localhost:3306/order_db"),
                    System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"),
                    System.getenv("SMARTDRIVE_DB_PASSWORD"));
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(OrderMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean OrderMapper orders(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(OrderMapper.class);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean NearbyDriverClient drivers() { return mock(NearbyDriverClient.class); }
        @Bean OrderAcceptanceTransaction transaction(OrderMapper orders) { return new OrderAcceptanceTransaction(orders); }
        @Bean OrderAcceptanceService acceptance(OrderMapper orders, NearbyDriverClient drivers,
                OrderAcceptanceTransaction transaction) { return new OrderAcceptanceService(orders, drivers, transaction); }
    }

    @Test void tenDriversCompeteForOneOrderWithOneCommittedWinnerInThreeRounds() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int round = 0; round < 3; round++) {
                long id = f.order();
                List<Long> drivers = new ArrayList<>();
                for (int i = 0; i < 10; i++) drivers.add(f.driverBase + round * 10 + i);
                f.notices(id, drivers);
                f.synchronizeEligibility(10);
                List<Outcome> results = f.race(drivers.stream().map(driver -> new Attempt(driver, id)).toList());
                assertEquals(1, results.stream().filter(r -> r.view != null).count());
                for (Outcome r : results) if (r.view == null) assertEquals(ErrorCode.ORDER_ALREADY_TAKEN, r.error);
                var winner = results.stream().filter(r -> r.view != null).findFirst().orElseThrow().view;
                assertEquals("ACCEPTED", f.orders.findById(id).status);
                assertEquals(winner.driverId(), f.orders.findById(id).driverId);
                assertEquals(1L, f.jdbc.queryForObject("SELECT COUNT(*) FROM driver_active_order WHERE order_id = ?", Long.class, id));
                assertEquals(id, f.orders.findActiveOrder(winner.driverId()));
                for (long driver : drivers) if (driver != winner.driverId()) assertNull(f.orders.findActiveOrder(driver));
                System.out.printf("Day10 same-order round=%d attempts=10 success=1 conflicts=9 active=1%n", round + 1);
            }
        }
    }

    @Test void oneDriverCompetingForTenOrdersHoldsOnlyOneInThreeRounds() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int round = 0; round < 3; round++) {
                long driver = f.driverBase + round;
                List<Attempt> attempts = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    long id = f.order();
                    f.notices(id, List.of(driver));
                    attempts.add(new Attempt(driver, id));
                }
                f.synchronizeEligibility(10);
                List<Outcome> results = f.race(attempts);
                assertEquals(1, results.stream().filter(r -> r.view != null).count());
                for (Outcome r : results) if (r.view == null) assertEquals(ErrorCode.DRIVER_BUSY, r.error);
                long winner = f.orders.findActiveOrder(driver);
                for (Attempt attempt : attempts) {
                    var order = f.orders.findById(attempt.order);
                    assertEquals(attempt.order == winner ? "ACCEPTED" : "WAITING", order.status);
                    assertEquals(attempt.order == winner ? Long.valueOf(driver) : null, order.driverId);
                }
                assertEquals(1L, f.jdbc.queryForObject("SELECT COUNT(*) FROM driver_active_order WHERE driver_id = ?", Long.class, driver));
                System.out.printf("Day10 same-driver round=%d attempts=10 success=1 busy=9 active=1%n", round + 1);
            }
        }
    }

    @Test void cancellationAfterPrecheckRollsBackInsertedOccupationAndDriverCanRetryAnotherOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            long id = f.order();
            long driver = f.driverBase;
            f.notices(id, List.of(driver));
            doAnswer(call -> {
                assertEquals(1, f.orders.cancelWaiting(id, f.passenger));
                return null;
            }).when(f.drivers).requireEligible(driver, id);
            assertEquals(ErrorCode.ORDER_NOT_WAITING, assertThrows(ApiException.class,
                    () -> f.acceptance.accept(new Principal(driver, "DRIVER"), id)).errorCode());
            assertEquals("CANCELLED", f.orders.findById(id).status);
            assertNull(f.orders.findActiveOrder(driver));
            long next = f.order();
            f.notices(next, List.of(driver));
            f.synchronizeEligibility(1);
            assertEquals("ACCEPTED", f.acceptance.accept(new Principal(driver, "DRIVER"), next).status());
            assertEquals(next, f.orders.findActiveOrder(driver));
        }
    }

    @Test void simultaneousCancelAndAcceptCommitExactlyOneTransition() throws Exception {
        try (Fixture f = new Fixture()) {
            OrderService passengers = new OrderService(f.orders, f.drivers, mock(OrderCreationTransaction.class));
            for (int i = 0; i < 6; i++) {
                long id = f.order();
                long driver = f.driverBase + i;
                f.notices(id, List.of(driver));
                CyclicBarrier start = new CyclicBarrier(2);
                doAnswer(call -> { start.await(20, TimeUnit.SECONDS); return null; })
                        .when(f.drivers).requireEligible(driver, id);
                Future<Outcome> accept = f.pool.submit(() -> f.attempt(new Attempt(driver, id)));
                Future<Boolean> cancel = f.pool.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    try { passengers.cancel(new Principal(f.passenger, "PASSENGER"), id); return true; }
                    catch (ApiException ex) { assertEquals(ErrorCode.ORDER_NOT_WAITING, ex.errorCode()); return false; }
                });
                Outcome accepted = accept.get(30, TimeUnit.SECONDS);
                boolean cancelled = cancel.get(30, TimeUnit.SECONDS);
                assertNotEquals(accepted.view != null, cancelled);
                var order = f.orders.findById(id);
                if (cancelled) {
                    assertEquals(ErrorCode.ORDER_NOT_WAITING, accepted.error);
                    assertEquals("CANCELLED", order.status);
                    assertNull(order.driverId);
                    assertNull(f.orders.findActiveOrder(driver));
                } else {
                    assertEquals("ACCEPTED", order.status);
                    assertEquals(driver, order.driverId);
                    assertEquals(id, f.orders.findActiveOrder(driver));
                }
            }
            System.out.println("Day10 cancel-vs-accept rounds=6 one-transition-per-round=PASS");
        }
    }

    record Attempt(long driver, long order) {}
    record Outcome(OrderView view, ErrorCode error) {}

    static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
        final OrderMapper orders = context.getBean(OrderMapper.class);
        final NearbyDriverClient drivers = context.getBean(NearbyDriverClient.class);
        final OrderAcceptanceService acceptance = context.getBean(OrderAcceptanceService.class);
        final JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        final JdbcTemplate driverJdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv().getOrDefault("MYSQL_DRIVER_URL", "jdbc:mysql://localhost:3306/driver_db"),
                System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"), System.getenv("SMARTDRIVE_DB_PASSWORD")));
        final ExecutorService pool = Executors.newFixedThreadPool(10);
        final long passenger = ThreadLocalRandom.current().nextLong(10_000_000_000L, 90_000_000_000L);
        final long driverBase = passenger + 100;
        final List<Long> ids = new ArrayList<>();

        long order() {
            String request = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ride_order(request_id, passenger_id, pickup_name, pickup_lng, pickup_lat,
                        dropoff_name, dropoff_lng, dropoff_lat, amount, status)
                    VALUES (?, ?, 'Day10 integration', 117.215, 39.136, 'Day10 end', 117.174, 39.104, 50.00, 'WAITING')
                    """, request, passenger);
            long id = orders.findByRequest(passenger, request).id;
            ids.add(id);
            return id;
        }

        void notices(long order, List<Long> driverIds) {
            for (long driver : driverIds) driverJdbc.update("INSERT INTO order_notice(order_id, driver_id) VALUES (?, ?)", order, driver);
        }

        void synchronizeEligibility(int participants) {
            reset(drivers);
            CyclicBarrier barrier = new CyclicBarrier(participants);
            doAnswer(call -> {
                long driver = call.getArgument(0);
                long order = call.getArgument(1);
                assertEquals(1L, driverJdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_notice WHERE order_id = ? AND driver_id = ?", Long.class, order, driver));
                barrier.await(20, TimeUnit.SECONDS);
                return null;
            }).when(drivers).requireEligible(anyLong(), anyLong());
        }

        List<Outcome> race(List<Attempt> attempts) throws Exception {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Attempt attempt : attempts) futures.add(pool.submit(() -> attempt(attempt)));
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) results.add(future.get(40, TimeUnit.SECONDS));
            return results;
        }

        Outcome attempt(Attempt attempt) {
            try { return new Outcome(acceptance.accept(new Principal(attempt.driver, "DRIVER"), attempt.order), null); }
            catch (ApiException ex) { return new Outcome(null, ex.errorCode()); }
        }

        @Override public void close() throws Exception {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            try {
                for (long id : ids) {
                    driverJdbc.update("DELETE FROM order_notice WHERE order_id = ? AND driver_id BETWEEN ? AND ?", id, driverBase, driverBase + 99);
                    jdbc.update("DELETE FROM driver_active_order WHERE order_id = ? AND driver_id BETWEEN ? AND ?", id, driverBase, driverBase + 99);
                    jdbc.update("DELETE FROM ride_order WHERE id = ? AND passenger_id = ?", id, passenger);
                }
            } finally { context.close(); }
        }
    }
}
