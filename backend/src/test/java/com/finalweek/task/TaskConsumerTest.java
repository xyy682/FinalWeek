package com.finalweek.task;

import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TaskConsumerTest {
    @Test void duplicateCancelledOrOldRoundMessageIsAcknowledgedWithoutRunningPipeline() throws Exception {
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

    @Test void retryableFailureRequeuesWithinBudgetAndRejectsAtBudgetLimit() throws Exception {
        var states = mock(TaskStateService.class); var tasks = mock(ParseTaskRepository.class);
        var pipeline = mock(TaskPipeline.class); var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class); var task = mock(ParseTask.class); var channel = mock(Channel.class);
        var properties = new TaskProperties("exchange", "parse", "queue", "dlx", "dlq",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofHours(24), 3);
        var consumer = new TaskConsumer(states, tasks, java.util.List.of(pipeline), properties, redisson);
        var taskId = UUID.randomUUID(); var command = new TaskMessage(taskId, 0, UUID.randomUUID().toString(), Instant.now());
        var messageProperties = new MessageProperties(); messageProperties.setDeliveryTag(7L);
        when(states.claim(taskId, 0, 3)).thenReturn(true);
        when(redisson.getLock("fw:task:lock:" + taskId)).thenReturn(lock);
        when(lock.tryLock(0, 10, java.util.concurrent.TimeUnit.MINUTES)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(tasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(task.getTaskType()).thenReturn(TaskType.PARSE_MATERIAL);
        when(task.getDeliveryAttemptCount()).thenReturn(1);
        when(pipeline.type()).thenReturn(TaskType.PARSE_MATERIAL);
        doThrow(new RetryableTaskException("AI_PROVIDER_TEMPORARY", "temporary")).when(pipeline).execute(task);

        consumer.consume(command, new Message(new byte[0], messageProperties), channel);

        verify(states).retrying(taskId, 0, "AI_PROVIDER_TEMPORARY", "temporary");
        verify(channel).basicNack(7L, false, true);
        verify(lock).unlock();

        reset(states, channel, lock);
        when(states.claim(taskId, 0, 3)).thenReturn(true);
        when(redisson.getLock("fw:task:lock:" + taskId)).thenReturn(lock);
        when(lock.tryLock(0, 10, java.util.concurrent.TimeUnit.MINUTES)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(task.getDeliveryAttemptCount()).thenReturn(3);

        consumer.consume(command, new Message(new byte[0], messageProperties), channel);

        verify(states).failed(taskId, 0, command.messageId(), "AI_PROVIDER_TEMPORARY", "temporary");
        verify(channel).basicReject(7L, false);
        verify(lock).unlock();
    }
}
