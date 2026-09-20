package com.campusbooking.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class RegistrationConfig {
    public static final String EXCHANGE = "registration.exchange";
    public static final String QUEUE = "registration.queue";
    public static final String ROUTING_KEY = "registration.create";
    public static final String DEAD_EXCHANGE = "registration.dead.exchange";
    public static final String DEAD_QUEUE = "registration.dead.queue";

    @Bean
    Declarables registrationTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY).build();
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(exchange, dead, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(dead).with(ROUTING_KEY));
    }

    @Bean
    Jackson2JsonMessageConverter registrationMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper, "com.campusbooking.registration");
    }
}
