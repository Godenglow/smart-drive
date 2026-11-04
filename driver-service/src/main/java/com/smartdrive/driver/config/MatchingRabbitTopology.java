package com.smartdrive.driver.config;

import com.smartdrive.common.api.event.OrderCreatedEvent;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatchingRabbitTopology {
    @Bean
    public Declarables matchingTopology() {
        var events = new TopicExchange("ride.events", true, false);
        var dead = new DirectExchange("ride.events.dlx", true, false);
        var matching = QueueBuilder.durable("driver.matching.q")
                .deadLetterExchange("ride.events.dlx").deadLetterRoutingKey("driver.matching.dlq").build();
        var dlq = QueueBuilder.durable("driver.matching.dlq").build();
        return new Declarables(events, dead, matching, dlq,
                BindingBuilder.bind(matching).to(events).with(OrderCreatedEvent.TYPE),
                BindingBuilder.bind(dlq).to(dead).with("driver.matching.dlq"));
    }
}
