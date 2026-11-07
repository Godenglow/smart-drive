package com.smartdrive.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.dto.Place;
import com.smartdrive.order.entity.OutboxEvent;
import com.smartdrive.order.mapper.OrderMapper;
import com.smartdrive.order.mapper.OutboxMapper;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** No Spring scheduler or broker consumer: each test touches only its own random passenger's events. */
class OutboxTestFixture implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final DriverManagerDataSource source = new DriverManagerDataSource(
            System.getenv().getOrDefault("MYSQL_ORDER_URL",
                    "jdbc:mysql://localhost:3306/order_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"),
            System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"),
            System.getenv("SMARTDRIVE_DB_PASSWORD"));
    final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final Principal passenger = new Principal(ThreadLocalRandom.current().nextLong(10_000_000_000L, 99_000_000_000L), "PASSENGER");
    final OrderMapper orders;
    final OutboxMapper outbox;
    final NearbyDriverClient drivers = mock(NearbyDriverClient.class);
    final OrderCreationTransaction creation;
    final OrderService service;

    OutboxTestFixture() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OrderMapper.class);
        configuration.addMapper(OutboxMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        orders = session.getMapper(OrderMapper.class);
        outbox = session.getMapper(OutboxMapper.class);
        when(drivers.hasNearby(any(), any())).thenReturn(true);
        creation = new OrderCreationTransaction(orders, outbox, json, manager);
        service = new OrderService(orders, drivers, creation);
    }

    CreateOrderRequest request() {
        var place = new Place("Step12-" + UUID.randomUUID(), new BigDecimal("117.215000"), new BigDecimal("39.136000"));
        return new CreateOrderRequest(UUID.randomUUID(), place, place);
    }

    OrderView create() { return service.create(passenger, request()); }

    long eventId(long orderId) {
        return jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id = ? AND event_type = 'order.created'", Long.class, orderId);
    }

    OutboxEvent event(long id) {
        // Test reads outside the publisher transaction do not own a persistent row lock.
        return outbox.lockPending(id);
    }

    @Override public void close() {
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT id FROM ride_order WHERE passenger_id = ?)", passenger.userId());
        jdbc.update("DELETE FROM ride_order WHERE passenger_id = ?", passenger.userId());
    }
}
