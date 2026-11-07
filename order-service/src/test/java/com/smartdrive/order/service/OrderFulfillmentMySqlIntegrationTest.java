package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.dto.Place;
import com.smartdrive.order.mapper.OrderMapper;
import com.smartdrive.order.mapper.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
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

/** Real MySQL, mapper SQL and Spring transaction proxies; remote driver eligibility is substituted. */
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
class OrderFulfillmentMySqlIntegrationTest {
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
            config.addMapper(OutboxMapper.class);
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
        @Bean NearbyDriverClient drivers() {
            NearbyDriverClient drivers = mock(NearbyDriverClient.class);
            when(drivers.hasNearby(any(), any())).thenReturn(true);
            return drivers;
        }
        @Bean OrderService passengers(OrderMapper orders, NearbyDriverClient drivers,
                SqlSessionFactory factory, DataSourceTransactionManager transactionManager) {
            OutboxMapper outbox = new SqlSessionTemplate(factory).getMapper(OutboxMapper.class);
            return new OrderService(orders, drivers, new OrderCreationTransaction(orders, outbox,
                    new ObjectMapper().findAndRegisterModules(), transactionManager));
        }
        @Bean OrderAcceptanceTransaction acceptanceTransaction(OrderMapper orders) {
            return new OrderAcceptanceTransaction(orders);
        }
        @Bean OrderAcceptanceService acceptance(OrderMapper orders, NearbyDriverClient drivers,
                OrderAcceptanceTransaction transaction) {
            return new OrderAcceptanceService(orders, drivers, transaction);
        }
        @Bean OrderFulfillmentService fulfillment(OrderMapper orders) { return new OrderFulfillmentService(orders); }
        @Bean OrderFulfillmentService failingFulfillment(OrderMapper orders) {
            OrderMapper fault = mock(OrderMapper.class, delegatesTo(orders));
            doAnswer(call -> {
                assertEquals(1, orders.releaseDriver(call.getArgument(0), call.getArgument(1)));
                throw new IllegalStateException("Step11 injected failure after real occupation deletion");
            }).when(fault).releaseDriver(anyLong(), anyLong());
            return new OrderFulfillmentService(fault);
        }
    }

    @Test void fullLifecycleReleasesBeforePaymentAndBothRolesHavePrivatePaginatedHistory() {
        try (Fixture f = new Fixture()) {
            long id = f.create(f.passenger);
            assertEquals("WAITING", f.orders.findById(id).status);
            assertEquals("ACCEPTED", f.acceptance.accept(f.driver, id).status());
            assertEquals(id, f.orders.findActiveOrder(f.driver.userId()));
            assertEquals("IN_SERVICE", f.service.start(f.driver, id).status());
            assertEquals("FINISHED", f.service.finish(f.driver, id).status());
            assertNull(f.orders.findActiveOrder(f.driver.userId()));
            long next = f.create(f.passenger);
            assertEquals("ACCEPTED", f.acceptance.accept(f.driver, next).status());
            assertEquals("PAID", f.service.payDemo(f.passenger, id).status());
            assertEquals(next, f.orders.findActiveOrder(f.driver.userId()));
            assertEquals(new BigDecimal("50.00"), f.orders.findById(id).amount);
            long cancelled = f.create(f.passenger);
            f.passengers.cancel(f.passenger, cancelled);
            f.create(new Principal(f.passenger.userId() + 1, "PASSENGER"));
            var history = f.passengers.mine(f.passenger, 1, 2);
            assertEquals(3, history.total());
            assertEquals(List.of(cancelled, next), history.items().stream().map(OrderView::id).toList());
            assertEquals("CANCELLED", history.items().getFirst().status());
            assertEquals(id, f.passengers.mine(f.passenger, 2, 2).items().getFirst().id());
            var driverHistory = f.passengers.mine(f.driver, 1, 10);
            assertEquals(2, driverHistory.total());
            assertEquals(List.of(next, id), driverHistory.items().stream().map(OrderView::id).toList());
            assertEquals("PAID", driverHistory.items().getLast().status());
            assertEquals(0, f.passengers.mine(new Principal(f.driver.userId() + 1, "DRIVER"), 1, 10).total());
            System.out.println("Step11 full-lifecycle=PAID released-before-payment=true histories=PASS amount=50.00");
        }
    }

    @Test void concurrentDuplicateStartFinishAndPaymentEachCommitOnceAndCannotReleaseNextOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int round = 0; round < 3; round++) {
                long id = f.create(f.passenger);
                f.acceptance.accept(f.driver, id);
                f.race(() -> f.service.start(f.driver, id), "IN_SERVICE");
                assertEquals(id, f.orders.findActiveOrder(f.driver.userId()));
                f.race(() -> f.service.finish(f.driver, id), "FINISHED");
                assertNull(f.orders.findActiveOrder(f.driver.userId()));
                long next = f.create(f.passenger);
                f.acceptance.accept(f.driver, next);
                f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.finish(f.driver, id));
                assertEquals(next, f.orders.findActiveOrder(f.driver.userId()));
                f.race(() -> f.service.payDemo(f.passenger, id), "PAID");
                assertEquals("PAID", f.orders.findById(id).status);
                assertEquals(next, f.orders.findActiveOrder(f.driver.userId()));
                f.service.start(f.driver, next);
                f.service.finish(f.driver, next);
                System.out.printf("Step11 duplicates round=%d start/finish/pay attempts=10 each success=1 conflicts=9 next-occupation-preserved=true%n", round + 1);
            }
        }
    }

    @Test void failureAfterActualDeletionRollsBackBothStatusAndOccupation() {
        try (Fixture f = new Fixture()) {
            long id = f.create(f.passenger);
            f.acceptance.accept(f.driver, id);
            f.service.start(f.driver, id);
            var failing = f.context.getBean("failingFulfillment", OrderFulfillmentService.class);
            assertThrows(IllegalStateException.class, () -> failing.finish(f.driver, id));
            assertEquals("IN_SERVICE", f.orders.findById(id).status);
            assertEquals(id, f.orders.findActiveOrder(f.driver.userId()));
            assertEquals("FINISHED", f.service.finish(f.driver, id).status());
            assertNull(f.orders.findActiveOrder(f.driver.userId()));
            System.out.println("Step11 failure-after-delete rollback-status-and-occupation=PASS retry=PASS");
        }
    }

    @Test void missingOrMismatchedOccupationRollsBackFinishAndCannotDeleteAnotherOrdersOccupation() {
        try (Fixture f = new Fixture()) {
            long id = f.create(f.passenger);
            f.acceptance.accept(f.driver, id);
            f.service.start(f.driver, id);
            assertEquals(1, f.orders.releaseDriver(f.driver.userId(), id));
            f.error(ErrorCode.INTERNAL_ERROR, () -> f.service.finish(f.driver, id));
            assertEquals("IN_SERVICE", f.orders.findById(id).status);
            long next = f.create(f.passenger);
            f.orders.occupyDriver(f.driver.userId(), next);
            f.error(ErrorCode.INTERNAL_ERROR, () -> f.service.finish(f.driver, id));
            assertEquals("IN_SERVICE", f.orders.findById(id).status);
            assertEquals(next, f.orders.findActiveOrder(f.driver.userId()));
        }
    }

    @Test void unauthorizedOrOutOfSequenceActionsLeaveDatabaseAndOccupationUntouched() {
        try (Fixture f = new Fixture()) {
            long id = f.create(f.passenger);
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.payDemo(f.passenger, id));
            f.acceptance.accept(f.driver, id);
            f.error(ErrorCode.FORBIDDEN, () -> f.service.start(f.passenger, id));
            f.error(ErrorCode.FORBIDDEN, () -> f.service.start(new Principal(f.driver.userId() + 1, "DRIVER"), id));
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.finish(f.driver, id));
            assertEquals("ACCEPTED", f.orders.findById(id).status);
            assertEquals(id, f.orders.findActiveOrder(f.driver.userId()));
            f.service.start(f.driver, id);
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.payDemo(f.passenger, id));
            f.service.finish(f.driver, id);
            f.error(ErrorCode.FORBIDDEN, () -> f.service.payDemo(new Principal(f.passenger.userId() + 1, "PASSENGER"), id));
            assertEquals("FINISHED", f.orders.findById(id).status);
            f.service.payDemo(f.passenger, id);
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.start(f.driver, id));
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.finish(f.driver, id));
            f.error(ErrorCode.ORDER_STATUS_INVALID, () -> f.service.payDemo(f.passenger, id));
            assertEquals("PAID", f.orders.findById(id).status);
            assertNull(f.orders.findActiveOrder(f.driver.userId()));
        }
    }

    static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
        final OrderMapper orders = context.getBean(OrderMapper.class);
        final OrderService passengers = context.getBean(OrderService.class);
        final OrderAcceptanceService acceptance = context.getBean(OrderAcceptanceService.class);
        final OrderFulfillmentService service = context.getBean("fulfillment", OrderFulfillmentService.class);
        final JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        final ExecutorService pool = Executors.newFixedThreadPool(10);
        final Principal passenger = new Principal(ThreadLocalRandom.current().nextLong(10_000_000_000L, 90_000_000_000L), "PASSENGER");
        final Principal driver = new Principal(passenger.userId() + 100, "DRIVER");
        final List<Long> ids = new ArrayList<>();

        long create(Principal owner) {
            Place place = new Place("Step11 integration", new BigDecimal("117.215"), new BigDecimal("39.136"));
            long id = passengers.create(owner, new CreateOrderRequest(UUID.randomUUID(), place, place)).id();
            ids.add(id);
            return id;
        }

        void race(Supplier<OrderView> action, String expected) throws Exception {
            CyclicBarrier start = new CyclicBarrier(10);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) futures.add(pool.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                try { assertEquals(expected, action.get().status()); return true; }
                catch (ApiException ex) { assertEquals(ErrorCode.ORDER_STATUS_INVALID, ex.errorCode()); return false; }
            }));
            int successes = 0;
            for (Future<Boolean> result : futures) if (result.get(40, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }

        void error(ErrorCode expected, Runnable action) {
            assertEquals(expected, assertThrows(ApiException.class, action::run).errorCode());
        }

        @Override public void close() {
            pool.shutdownNow();
            try {
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
                for (long id : ids) {
                    jdbc.update("DELETE FROM driver_active_order WHERE order_id = ? AND driver_id = ?", id, driver.userId());
                    jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", id);
                    jdbc.update("DELETE FROM ride_order WHERE id = ? AND passenger_id IN (?, ?)", id, passenger.userId(), passenger.userId() + 1);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            } finally { context.close(); }
        }
    }
}
