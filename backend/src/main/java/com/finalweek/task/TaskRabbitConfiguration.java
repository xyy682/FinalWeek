package com.finalweek.task;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskRabbitConfiguration {
    @Bean DirectExchange taskExchange(TaskProperties p) { return new DirectExchange(p.exchange(), true, false); }
    @Bean DirectExchange taskDeadLetterExchange(TaskProperties p) {
        return new DirectExchange(p.deadLetterExchange(), true, false);
    }
    @Bean Queue taskQueue(TaskProperties p) {
        return QueueBuilder.durable(p.queue()).deadLetterExchange(p.deadLetterExchange())
                .deadLetterRoutingKey(p.routingKey()).build();
    }
    @Bean Queue taskDeadLetterQueue(TaskProperties p) { return QueueBuilder.durable(p.deadLetterQueue()).build(); }
    @Bean Binding taskBinding(TaskProperties p, Queue taskQueue, DirectExchange taskExchange) {
        return BindingBuilder.bind(taskQueue).to(taskExchange).with(p.routingKey());
    }
    @Bean Binding taskDeadLetterBinding(TaskProperties p, Queue taskDeadLetterQueue,
                                        DirectExchange taskDeadLetterExchange) {
        return BindingBuilder.bind(taskDeadLetterQueue).to(taskDeadLetterExchange).with(p.routingKey());
    }
    @Bean Jackson2JsonMessageConverter taskMessageConverter() { return new Jackson2JsonMessageConverter(); }
    @Bean RabbitTemplate rabbitTemplate(ConnectionFactory factory, Jackson2JsonMessageConverter converter) {
        var template = new RabbitTemplate(factory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }
}
