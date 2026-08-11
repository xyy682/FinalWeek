package com.finalweek.task;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class TaskConsumer {
    private final TaskStateService states;
    private final ParseTaskRepository tasks;
    private final List<TaskPipeline> pipelines;
    private final TaskProperties properties;
    private final RedissonClient redisson;
    public TaskConsumer(TaskStateService states, ParseTaskRepository tasks, List<TaskPipeline> pipelines,
                        TaskProperties properties, RedissonClient redisson) {
        this.states = states; this.tasks = tasks; this.pipelines = pipelines;
        this.properties = properties; this.redisson = redisson;
    }

    @RabbitListener(queues = "${finalweek.task.queue}")
    public void consume(TaskMessage command, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        if (!states.claim(command.taskId(), command.executionRound(), properties.maxDeliveryAttempts())) {
            channel.basicAck(tag, false); return;
        }
        var lock = redisson.getLock("fw:task:lock:" + command.taskId());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
            if (!acquired) {
                retryOrFail(command, tag, channel, "TASK_LOCK_BUSY", "任务正在另一消费者执行"); return;
            }
            var task = tasks.findById(command.taskId()).orElseThrow();
            var pipeline = pipelines.stream().filter(value -> value.type() == task.getTaskType()).findFirst()
                    .orElseThrow(() -> new PermanentTaskException("TASK_TYPE_UNSUPPORTED", "任务类型没有处理器"));
            pipeline.execute(task);
            states.succeeded(command.taskId(), command.executionRound());
            channel.basicAck(tag, false);
        } catch (RetryableTaskException exception) {
            var task = tasks.findById(command.taskId()).orElseThrow();
            if (task.getDeliveryAttemptCount() >= properties.maxDeliveryAttempts()) {
                states.failed(command.taskId(), command.executionRound(), command.messageId(),
                        exception.code(), exception.getMessage());
                channel.basicReject(tag, false);
            } else {
                states.retrying(command.taskId(), command.executionRound(), exception.code(), exception.getMessage());
                channel.basicNack(tag, false, true);
            }
        } catch (PermanentTaskException exception) {
            states.failed(command.taskId(), command.executionRound(), command.messageId(),
                    exception.code(), exception.getMessage());
            channel.basicReject(tag, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            states.retrying(command.taskId(), command.executionRound(), "TASK_INTERRUPTED", "任务执行被中断");
            channel.basicNack(tag, false, true);
        } catch (RuntimeException exception) {
            retryOrFail(command, tag, channel, "TASK_INTERNAL_ERROR", exception.getMessage());
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void retryOrFail(TaskMessage command, long tag, Channel channel, String code, String message)
            throws IOException {
        var task = tasks.findById(command.taskId()).orElseThrow();
        if (task.getDeliveryAttemptCount() >= properties.maxDeliveryAttempts()) {
            states.failed(command.taskId(), command.executionRound(), command.messageId(), code, message);
            channel.basicReject(tag, false);
        } else {
            states.retrying(command.taskId(), command.executionRound(), code, message);
            channel.basicNack(tag, false, true);
        }
    }
}
