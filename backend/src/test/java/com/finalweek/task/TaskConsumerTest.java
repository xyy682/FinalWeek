package com.finalweek.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TaskConsumerTest {
    @Test
    void duplicateCancelledOrOldRoundMessageIsAcknowledgedWithoutRunningPipeline() throws Exception {
        var states = mock(TaskStateService.class);
        var tasks = mock(BackgroundTaskRepository.class);
        var pipeline = mock(TaskPipeline.class);
        var leases = mock(TaskLeaseHeartbeat.class);
        var properties = properties();
        var consumer = new TaskConsumer(states, tasks, java.util.List.of(pipeline), properties, leases);
        var taskId = UUID.randomUUID();
        var command = new TaskMessage(taskId, 1, UUID.randomUUID().toString(), Instant.now());
        var messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(42L);
        var channel = mock(Channel.class);
        when(states.claim(eq(taskId), eq(1), eq(3), eq(command.messageId()), any(Instant.class))).thenReturn(false);

        consumer.consume(command, new Message(new byte[0], messageProperties), channel);

        verify(channel).basicAck(42L, false);
        verifyNoInteractions(pipeline, leases);
    }

    @Test
    void retryableFailureRequeuesWhenOwnerTransitionsTaskToRetrying() throws Exception {
        var fixture = fixture(1, 7L);
        doThrow(new RetryableTaskException("AI_PROVIDER_TEMPORARY", "temporary"))
                .when(fixture.pipeline()).execute(fixture.task());
        when(fixture.states().retrying(fixture.taskId(), 0, fixture.command().messageId(),
                "AI_PROVIDER_TEMPORARY", "temporary")).thenReturn(true);

        fixture.consumer().consume(fixture.command(), fixture.message(), fixture.channel());

        verify(fixture.states()).retrying(fixture.taskId(), 0, fixture.command().messageId(),
                "AI_PROVIDER_TEMPORARY", "temporary");
        verify(fixture.channel()).basicNack(7L, false, true);
        verify(fixture.lease()).close();
    }

    @Test
    void retryableFailureRejectsAtBudgetLimit() throws Exception {
        var fixture = fixture(3, 8L);
        doThrow(new RetryableTaskException("AI_PROVIDER_TEMPORARY", "temporary"))
                .when(fixture.pipeline()).execute(fixture.task());
        when(fixture.states().failed(fixture.taskId(), 0, fixture.command().messageId(),
                fixture.command().messageId(), "AI_PROVIDER_TEMPORARY", "temporary")).thenReturn(true);

        fixture.consumer().consume(fixture.command(), fixture.message(), fixture.channel());

        verify(fixture.states()).failed(fixture.taskId(), 0, fixture.command().messageId(),
                fixture.command().messageId(), "AI_PROVIDER_TEMPORARY", "temporary");
        verify(fixture.channel()).basicReject(8L, false);
        verify(fixture.lease()).close();
    }

    @Test
    void consumerAcknowledgesWithoutStateChangeAfterLeaseOwnershipWasLost() throws Exception {
        var fixture = fixture(1, 9L);
        doThrow(new RetryableTaskException("AI_PROVIDER_TEMPORARY", "temporary"))
                .when(fixture.pipeline()).execute(fixture.task());
        when(fixture.states().retrying(fixture.taskId(), 0, fixture.command().messageId(),
                "AI_PROVIDER_TEMPORARY", "temporary")).thenReturn(false);

        fixture.consumer().consume(fixture.command(), fixture.message(), fixture.channel());

        verify(fixture.channel()).basicAck(9L, false);
        verify(fixture.channel(), never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    private Fixture fixture(int deliveryAttempts, long tag) {
        var states = mock(TaskStateService.class);
        var tasks = mock(BackgroundTaskRepository.class);
        var pipeline = mock(TaskPipeline.class);
        var leases = mock(TaskLeaseHeartbeat.class);
        var lease = mock(TaskLeaseHeartbeat.Lease.class);
        var task = mock(BackgroundTask.class);
        var channel = mock(Channel.class);
        var taskId = UUID.randomUUID();
        var command = new TaskMessage(taskId, 0, UUID.randomUUID().toString(), Instant.now());
        var messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(tag);
        var message = new Message(new byte[0], messageProperties);
        when(states.claim(eq(taskId), eq(0), eq(3), eq(command.messageId()), any(Instant.class))).thenReturn(true);
        when(leases.start(taskId, 0, command.messageId())).thenReturn(lease);
        when(tasks.findById(taskId)).thenReturn(java.util.Optional.of(task));
        when(task.getTaskType()).thenReturn(TaskType.PARSE_MATERIAL);
        when(task.getDeliveryAttemptCount()).thenReturn(deliveryAttempts);
        when(pipeline.type()).thenReturn(TaskType.PARSE_MATERIAL);
        return new Fixture(new TaskConsumer(states, tasks, java.util.List.of(pipeline), properties(), leases),
                states, taskId, command, message, channel, pipeline, task, lease);
    }

    private TaskProperties properties() {
        return new TaskProperties("exchange", "parse", "queue", "dlx", "dlq",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofHours(24), 3);
    }

    private record Fixture(TaskConsumer consumer, TaskStateService states, UUID taskId, TaskMessage command,
                           Message message, Channel channel, TaskPipeline pipeline, BackgroundTask task,
                           TaskLeaseHeartbeat.Lease lease) {}
}