package com.campusbooking.registration;

import com.campusbooking.config.RegistrationConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class RegistrationListener {
    private static final Logger log = LoggerFactory.getLogger(RegistrationListener.class);
    private final RegistrationConsumerService service;
    public RegistrationListener(RegistrationConsumerService service) { this.service = service; }

    @RabbitListener(id = "registrationConsumer", queues = RegistrationConfig.QUEUE, ackMode = "MANUAL")
    public void receive(RegistrationMessage message, Message delivery, Channel channel) throws IOException {
        long tag = delivery.getMessageProperties().getDeliveryTag();
        try {
            var result = service.consume(message);
            log.info("Registration committed requestId={} status={}", message.requestId(), result.status());
        } catch (RuntimeException error) {
            log.error("Registration consume failed requestId={} type={}; reject to dead letter queue",
                    message.requestId(), error.getClass().getSimpleName(), error);
            channel.basicReject(tag, false);
            return;
        }
        // service 的事务已提交才 ACK。ACK 失败由连接恢复后重投，不能再把终态改成 FAILED。
        channel.basicAck(tag, false);
    }
}
