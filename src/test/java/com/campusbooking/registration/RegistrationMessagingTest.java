package com.campusbooking.registration;

import com.campusbooking.config.RegistrationConfig;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RegistrationMessagingTest {
    private final RegistrationMessage request = new RegistrationMessage("123456789", 1, 2, 1000);

    @Test
    void ackOnlyAfterServiceReturnsCommittedResult() throws Exception {
        var service = mock(RegistrationConsumerService.class);
        var channel = mock(Channel.class);
        when(service.consume(request)).thenReturn(new RegistrationViews.Result(request.requestId(), 1, 2,
                "SUCCESS", 3L, null, Instant.now()));
        new RegistrationListener(service).receive(request, delivery(), channel);
        var order = inOrder(service, channel);
        order.verify(service).consume(request);
        order.verify(channel).basicAck(7, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void failedTransactionRejectsWithoutRequeueOrAck() throws Exception {
        var service = mock(RegistrationConsumerService.class);
        var channel = mock(Channel.class);
        when(service.consume(request)).thenThrow(new IllegalStateException("transaction failed"));
        new RegistrationListener(service).receive(request, delivery(), channel);
        verify(channel).basicReject(7, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void ackFailurePropagatesWithoutRejectingAlreadyCommittedMessage() throws Exception {
        var service = mock(RegistrationConsumerService.class);
        var channel = mock(Channel.class);
        when(service.consume(request)).thenReturn(new RegistrationViews.Result(request.requestId(), 1, 2,
                "SUCCESS", 3L, null, Instant.now()));
        doThrow(new IOException("connection lost after commit")).when(channel).basicAck(7, false);
        assertThatThrownBy(() -> new RegistrationListener(service).receive(request, delivery(), channel)).isInstanceOf(IOException.class);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void negativeConfirmAndReturnedMessageLogOriginalRequestId(CapturedOutput output) {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            return null;
        }).when(rabbit).convertAndSend(eq(RegistrationConfig.EXCHANGE), eq(RegistrationConfig.ROUTING_KEY),
                eq(request), any(MessagePostProcessor.class), any(CorrelationData.class));
        new RegistrationPublisher(rabbit).send(request);
        assertThat(output).contains("publish unconfirmed requestId=123456789", "broker nack");

        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            MessagePostProcessor processor = invocation.getArgument(3);
            Message outgoing = processor.postProcessMessage(delivery());
            assertThat(outgoing.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(outgoing.getMessageProperties().getMessageId()).isEqualTo(request.requestId());
            correlation.setReturned(new ReturnedMessage(outgoing, 312, "NO_ROUTE", RegistrationConfig.EXCHANGE, "missing"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(RegistrationConfig.EXCHANGE), eq(RegistrationConfig.ROUTING_KEY),
                eq(request), any(MessagePostProcessor.class), any(CorrelationData.class));
        new RegistrationPublisher(rabbit).send(request);
        assertThat(output).contains("returned=true");
    }

    @Test
    void confirmTimeoutIsLoggedWithoutResending(CapturedOutput output) {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        new RegistrationPublisher(rabbit).send(request);
        await().atMost(Duration.ofSeconds(7)).untilAsserted(() ->
                assertThat(output).contains("publish unconfirmed requestId=123456789", "TimeoutException"));
        verify(rabbit, times(1)).convertAndSend(anyString(), anyString(), eq(request),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private Message delivery() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7);
        return new Message(new byte[0], properties);
    }
}
