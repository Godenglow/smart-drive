package com.smartdrive.order.config;

import com.smartdrive.common.api.event.OrderCreatedEvent;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
    public static final String EVENTS = "ride.events";
    public static final String MATCHING_QUEUE = "driver.matching.q";
    public static final String DEAD_EXCHANGE = "ride.events.dlx";
    public static final String DEAD_QUEUE = "driver.matching.dlq";

    @Bean
    public Declarables orderEventTopology() {
        var events = new TopicExchange(EVENTS, true, false);
        var dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        var matching = QueueBuilder.durable(MATCHING_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(DEAD_QUEUE).build();
        var dlq = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(events, dead, matching, dlq,
                BindingBuilder.bind(matching).to(events).with(OrderCreatedEvent.TYPE),
                BindingBuilder.bind(dlq).to(dead).with(DEAD_QUEUE));
    }
}
