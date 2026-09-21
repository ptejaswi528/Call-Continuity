package com.call.callserver;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class CallCommandSender {

    private static RabbitTemplate staticRabbitTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @PostConstruct
    public void init() {
        staticRabbitTemplate = this.rabbitTemplate;
    }

    public static void sendCommandToPhone(String routingKey) {
        if (staticRabbitTemplate != null) {
            String payload = "{\"command\":\"" + routingKey + "\"}";
            staticRabbitTemplate.convertAndSend("call_exchange", routingKey, payload);
            System.out.println("🚀 MAC COMMAND SENT: " + routingKey);
        } else {
            System.err.println("❌ RabbitTemplate not initialized in CallCommandSender!");
        }
    }
}