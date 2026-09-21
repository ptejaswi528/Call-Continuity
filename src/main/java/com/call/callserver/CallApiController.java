package com.call.callserver;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calls")
public class CallApiController{
    private final RabbitTemplate rabbitTemplate;

    public CallApiController(RabbitTemplate rabbitTemplate){
        this.rabbitTemplate=rabbitTemplate;
    }

    @PostMapping("/event/{routingKey}")
    public String handleCallEvent(@PathVariable String routingKey,@RequestBody String payload){
        rabbitTemplate.convertAndSend("call_exchange",routingKey,payload);
        return "EVENT ROUTED TO"+routingKey;
    }


}