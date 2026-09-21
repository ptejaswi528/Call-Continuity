package com.call.callserver;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class CallEventConsumer {

    @RabbitListener(queues="mac_queue")
    public void receiveMac(String message){
        System.out.println("\n--- NEW MESSAGE ARRIVED ---");
        System.out.println("Raw payload: " + message);

        try {
            
            ObjectMapper obj= new ObjectMapper();
            JsonNode jsonnode= obj.readTree(message);
            JsonNode numberNode = jsonnode.get("number");
            if (numberNode == null) {
                System.out.println("⏭️ Skipping non-call message (no 'number' field)");
                return;
            }
            String number = numberNode.asText();
            System.out.println("Successfully extracted number: " + number);
            CallDesktopUI.updateUI(number);

        } catch (Exception e) {
            System.out.println("❌ Failed to read the message: " + e.getMessage());

        }


    }
    // @RabbitListener(queues="phone_queue")
    // public void receivePhone(String message){
    //     System.out.println("PHONE NOTIFICATION RECEIVED");
    //     System.out.println("MESSAGE: "+message+"/n");
        
    // }

    
}
