package com.finalweek.task;

import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TaskConsumerTest {
    @Test void oldExecutionRoundIsAcknowledgedWithoutRunningPipeline() throws Exception {
        var states = mock(TaskStateService.class); var tasks = mock(ParseTaskRepository.class);
        var pipeline = mock(TaskPipeline.class); var redisson = mock(RedissonClient.class);
        var properties = new TaskProperties("exchange", "parse", "queue", "dlx", "dlq",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofHours(24), 3);
        var consumer = new TaskConsumer(states, tasks, java.util.List.of(pipeline), properties, redisson);
        var taskId = UUID.randomUUID();
        var command = new TaskMessage(taskId, 1, UUID.randomUUID().toString(), Instant.now());
        var messageProperties = new MessageProperties(); messageProperties.setDeliveryTag(42L);
        var channel = mock(Channel.class);
        when(states.claim(taskId, 1, 3)).thenReturn(false);

        consumer.consume(command, new Message(new byte[0], messageProperties), channel);

        verify(channel).basicAck(42L, false);
        verifyNoInteractions(pipeline, redisson);
    }
}
