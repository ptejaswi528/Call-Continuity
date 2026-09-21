package com.call.callserver;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange("call_exchange");
    }

    @Bean
    public Queue macQueue() {
        return new Queue("mac_queue_clean");
    }

    @Bean
    public Queue macCallQueue() {
        return new Queue("mac_queue");
    }

    @Bean
    public Queue phoneQueue() {
        return new Queue("phone_queue_clean");

    }

    @Bean
    public Binding macBinding(DirectExchange callExchange, Queue macCallQueue) {
        return BindingBuilder.bind(macCallQueue).to(callExchange).with("incoming.call");
    }

    @Bean
    public Binding phoneBindingAccept(DirectExchange callExchange, Queue phoneQueue) {
        return BindingBuilder.bind(phoneQueue).to(callExchange).with("accept.call");
    }

    @Bean
    public Binding phoneBindingReject(DirectExchange callExchange, Queue phoneQueue) {
        return BindingBuilder.bind(phoneQueue).to(callExchange).with("reject.call");
    }

    @Bean
    public Binding phoneBindingEnd(DirectExchange callExchange, Queue phoneQueue) {
        return BindingBuilder.bind(phoneQueue).to(callExchange).with("end.call");
    }

    @Bean
    public Binding macWebRTCOffer(DirectExchange callExchange, Queue macQueue) {
        return BindingBuilder.bind(macQueue).to(callExchange).with("webrtc.offer");
    }

    @Bean
    public Binding phoneWebRTCOffer(DirectExchange callExchange, Queue phoneQueue) {
        return BindingBuilder.bind(phoneQueue).to(callExchange).with("webrtc.answer");
    }

    @Bean
    public Binding macWebRTCIce(DirectExchange callExchange, Queue macQueue) {
        return BindingBuilder.bind(macQueue).to(callExchange).with("webrtc.ice.mac");
    }

    @Bean
    public Binding phoneWebRTCIce(DirectExchange callExchange, Queue phoneQueue) {
        return BindingBuilder.bind(phoneQueue).to(callExchange).with("webrtc.ice.phone");
    }

}