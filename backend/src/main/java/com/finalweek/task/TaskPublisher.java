package com.finalweek.task;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaskPublisher {
    private final RabbitTemplate rabbit;
    private final TaskProperties properties;
    public TaskPublisher(RabbitTemplate rabbit, TaskProperties properties) {
        this.rabbit = rabbit; this.properties = properties;
    }
    public TaskMessage publish(ParseTask task) {
        var body = TaskMessage.create(task);
        var correlation = new CorrelationData(body.messageId());
        rabbit.convertAndSend(properties.exchange(), properties.routingKey(), body, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setMessageId(body.messageId());
            return message;
        }, correlation);
        try {
            var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ publisher confirm nack: " + confirm.getReason());
            return body;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RabbitMQ confirm 被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("RabbitMQ publisher confirm 超时或失败", exception);
        }
    }
}
