package com.campusbooking.registration;

import com.campusbooking.config.RegistrationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class RegistrationPublisher {
    private static final Logger log = LoggerFactory.getLogger(RegistrationPublisher.class);
    private final RabbitTemplate rabbit;
    public RegistrationPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public void send(RegistrationMessage message) {
        CorrelationData correlation = new CorrelationData(message.requestId());
        correlation.getFuture().orTimeout(5, TimeUnit.SECONDS).whenComplete((confirm, error) -> {
            if (error != null || !confirm.isAck() || correlation.getReturned() != null) {
                String reason = error != null ? error.getClass().getSimpleName()
                        : correlation.getReturned() != null ? correlation.getReturned().getReplyText() : confirm.getReason();
                log.warn("Registration publish unconfirmed requestId={} returned={} reason={}",
                        message.requestId(), correlation.getReturned() != null, reason);
            } else {
                log.info("Registration publish confirmed requestId={}", message.requestId());
            }
        });
        rabbit.convertAndSend(RegistrationConfig.EXCHANGE, RegistrationConfig.ROUTING_KEY, message, outgoing -> {
            outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outgoing.getMessageProperties().setMessageId(message.requestId());
            return outgoing;
        }, correlation);
    }
}
