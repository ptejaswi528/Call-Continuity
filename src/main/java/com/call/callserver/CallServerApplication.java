package com.call.callserver;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javafx.application.Application;


@SpringBootApplication
@RestController
public class CallServerApplication {
    public static void main(String[]args){
        Application.launch(CallDesktopUI.class,args);
    }

    @GetMapping("/")
    public String serverCheck(){
        return "SERVER RUNNING";
    }
    @GetMapping("/hello")
    public String helloCheck() {
        return "HELLO";
    }
    @GetMapping("/tejaswi")
    public String tejaswi(){
        return "I LOVE YOUUU TEJASWIIIII";
    }
    
}
