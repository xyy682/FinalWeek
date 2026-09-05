package com.finalweek.task;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TaskConsumer {
    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);
    private final TaskStateService states;
    private final BackgroundTaskRepository tasks;
    private final List<TaskPipeline> pipelines;
    private final TaskProperties properties;
    private final TaskLeaseHeartbeat leases;

    public TaskConsumer(TaskStateService states, BackgroundTaskRepository tasks, List<TaskPipeline> pipelines,
                        TaskProperties properties, TaskLeaseHeartbeat leases) {
        this.states = states;
        this.tasks = tasks;
        this.pipelines = pipelines;
        this.properties = properties;
        this.leases = leases;
    }

    @RabbitListener(queues = "${finalweek.task.queue}")
    public void consume(TaskMessage command, Message message, Channel channel) throws IOException {
        long consumeStarted = System.nanoTime();
        long tag = message.getMessageProperties().getDeliveryTag();
        String owner = command.messageId();
        if (!states.claim(command.taskId(), command.executionRound(), properties.maxDeliveryAttempts(), owner,
                Instant.now().plus(properties.processingLease()))) {
            channel.basicAck(tag, false);
            return;
        }
        try (var ignored = leases.start(command.taskId(), command.executionRound(), owner)) {
            var task = tasks.findById(command.taskId()).orElseThrow();
            var pipeline = pipelines.stream().filter(value -> value.type() == task.getTaskType()).findFirst()
                    .orElseThrow(() -> new PermanentTaskException("TASK_TYPE_UNSUPPORTED", "任务类型没有处理器"));
            pipeline.execute(task);
            if (!states.succeeded(command.taskId(), command.executionRound(), owner)) {
                log.warn("Task completed after lease ownership was lost taskId={} executionRound={} owner={}",
                        command.taskId(), command.executionRound(), owner);
            }
            channel.basicAck(tag, false);
        } catch (RetryableTaskException exception) {
            retryOrFail(command, owner, tag, channel, exception.code(), exception.getMessage());
        } catch (PermanentTaskException exception) {
            if (states.failed(command.taskId(), command.executionRound(), owner, command.messageId(),
                    exception.code(), exception.getMessage())) {
                channel.basicReject(tag, false);
            } else {
                channel.basicAck(tag, false);
            }
        } catch (RuntimeException exception) {
            retryOrFail(command, owner, tag, channel, "TASK_INTERNAL_ERROR", exception.getMessage());
        } finally {
            tasks.findById(command.taskId()).ifPresent(task -> log.info(
                    "Task execution observed taskId={} status={} executionRound={} deliveryAttempts={} apiCalls={} totalDurationMs={}",
                    task.getId(), task.getStatus(), task.getExecutionRound(), task.getDeliveryAttemptCount(),
                    task.getApiAttemptCount(), (System.nanoTime() - consumeStarted) / 1_000_000));
        }
    }

    private void retryOrFail(TaskMessage command, String owner, long tag, Channel channel, String code, String message)
            throws IOException {
        var task = tasks.findById(command.taskId()).orElseThrow();
        if (task.getDeliveryAttemptCount() >= properties.maxDeliveryAttempts()) {
            if (states.failed(command.taskId(), command.executionRound(), owner, command.messageId(), code, message)) {
                channel.basicReject(tag, false);
            } else {
                channel.basicAck(tag, false);
            }
        } else if (states.retrying(command.taskId(), command.executionRound(), owner, code, message)) {
            channel.basicNack(tag, false, true);
        } else {
            channel.basicAck(tag, false);
        }
    }
}