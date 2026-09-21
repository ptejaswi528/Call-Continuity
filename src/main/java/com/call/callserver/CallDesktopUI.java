package com.call.callserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class CallDesktopUI extends Application {

    private ConfigurableApplicationContext context;
    private static Label statusLabel;
    private static Label numberLabel;
    private static VBox root;
    private static HBox buttonBox;

    private DesktopWebRTCManager rtcManager;
    private Connection rabbitConnection;
    private Channel rabbitChannel;

    @Override
    public void init() {
        this.context = new SpringApplicationBuilder()
                .sources(CallServerApplication.class)
                .run();
    }

    @Override
    public void start(Stage primaryStage) {


        rtcManager = new DesktopWebRTCManager(new DesktopWebRTCManager.WebSignalingListener() {
            @Override
            public void onAnswerCreated(String sdp) {
                JSONObject json = new JSONObject();
                json.put("type", "ANSWER");
                json.put("sdp", sdp);
                sendWebRTCPayload("webrtc.answer", json);
            }

            @Override
            public void onIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
                JSONObject json = new JSONObject();
                json.put("type", "ICE");
                json.put("candidate", candidate);
                json.put("sdpMid", sdpMid);
                json.put("sdpMLineIndex", sdpMLineIndex);
                sendWebRTCPayload("webrtc.ice.phone", json);
            }
        });

        startRabbitMQListener();

        statusLabel = new Label("📡 SYSTEM ACTIVE");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        statusLabel.setTextFill(Color.GRAY);

        numberLabel = new Label("Waiting for calls...");
        numberLabel.setFont(Font.font("System", FontWeight.MEDIUM, 24));

        Button hangupBtn = new Button("🛑 End Call");
        hangupBtn.setStyle(
                "-fx-background-color: #d32f2f; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 5;");

        Button acceptBtn = new Button("✅ Answer");
        acceptBtn.setStyle(
                "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 5;");

        acceptBtn.setOnAction(e -> {
            CallCommandSender.sendCommandToPhone("accept.call");
            statusLabel.setText("🗣️ CALL CONNECTED");
            statusLabel.setTextFill(Color.GREEN);
            root.setStyle(
                    "-fx-background-color: #e8f5e9; -fx-padding: 30; -fx-background-radius: 15; -fx-border-color: #81c784; -fx-border-width: 2;");

            buttonBox.getChildren().clear();
            buttonBox.getChildren().add(hangupBtn);
        });

        Button rejectBtn = new Button("❌ Reject");
        rejectBtn.setStyle(
                "-fx-background-color: #F44336; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 5;");

        rejectBtn.setOnAction(e -> {
            CallCommandSender.sendCommandToPhone("reject.call");
            rtcManager.dispose();
            statusLabel.setText("📡 SYSTEM ACTIVE");
            statusLabel.setTextFill(Color.GRAY);
            numberLabel.setText("Waiting for calls...");
            root.setStyle("-fx-background-color: white; -fx-padding: 30; -fx-background-radius: 15;");
            buttonBox.setVisible(false);
        });

        hangupBtn.setOnAction(e -> {
            CallCommandSender.sendCommandToPhone("end.call");
            rtcManager.dispose();

            statusLabel.setText("📡 SYSTEM ACTIVE");
            statusLabel.setTextFill(Color.GRAY);
            numberLabel.setText("Waiting for calls...");
            root.setStyle("-fx-background-color: white; -fx-padding: 30; -fx-background-radius: 15;");

            buttonBox.setVisible(false);

            buttonBox.getChildren().clear();
            buttonBox.getChildren().addAll(acceptBtn, rejectBtn);
        });

        buttonBox = new HBox(20, acceptBtn, rejectBtn);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setVisible(false);

        root = new VBox(15, statusLabel, numberLabel, buttonBox);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: white; -fx-padding: 30; -fx-background-radius: 15;");

        Scene scene = new Scene(root, 400, 200);
        scene.setFill(Color.TRANSPARENT);

        primaryStage.initStyle(StageStyle.DECORATED);
        primaryStage.setTitle("Call Mirror");
        primaryStage.setScene(scene);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.show();
    }

    public static void updateUI(String number) {
        Platform.runLater(() -> {
            root.setStyle(
                    "-fx-background-color: #ffebee; -fx-padding: 30; -fx-background-radius: 15; -fx-border-color: #ffcdd2; -fx-border-width: 2;");
            statusLabel.setText("🚨 INCOMING CALL");
            statusLabel.setTextFill(Color.RED);
            numberLabel.setText(number);
            buttonBox.setVisible(true);
        });
    }


    //Main logic
    private void startRabbitMQListener() {
        new Thread(() -> {
            try {
                System.out.println("📬 Opening RabbitMQ Mailbox on Mac...");
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost("192.168.1.12"); //IP address
                factory.setUsername("guest");
                factory.setPassword("guest");

                rabbitConnection = factory.newConnection();
                rabbitChannel = rabbitConnection.createChannel();

                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);

                    if (message.startsWith("{") && message.contains("\"type\"")) {
                        try {
                            JSONObject json = new JSONObject(message);
                            String type = json.getString("type");

                            if ("OFFER".equals(type)) {
                                System.out.println("🤝 Caught SDP Offer. Booting audio engine...");
                                String sdp = json.getString("sdp");
                                rtcManager.receiveOfferAndAnswer(sdp);

                            } else if ("ICE".equals(type)) {
                                String candidate = json.getString("candidate");
                                String sdpMid = json.getString("sdpMid");
                                int sdpMLineIndex = json.getInt("sdpMLineIndex");
                                rtcManager.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex);
                            }
                        } catch (Exception e) {
                            System.err.println("❌ Failed to parse WebRTC JSON: " + e.getMessage());
                        }
                    } else {
                        System.out.println("🤖 Standard Mac Command Received: " + message);
                    }
                };

                rabbitChannel.basicConsume("mac_queue_clean", true, deliverCallback, consumerTag -> {
                });

            } catch (Exception e) {
                System.err.println("❌ Mac RabbitMQ Error: " + e.getMessage());
            }
        }).start();
    }

    private static void sendWebRTCPayload(String routingKey, JSONObject payload) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://192.168.1.12:8080/api/calls/event/" + routingKey)) //IP address
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> System.out.println("✅ Sent " + routingKey + " to Switchboard!"));
        } catch (Exception e) {
            System.err.println("❌ Failed to send " + routingKey + ": " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        try {
            if (rtcManager != null)
                rtcManager.dispose();
            if (rabbitChannel != null)
                rabbitChannel.close();
            if (rabbitConnection != null)
                rabbitConnection.close();
        } catch (Exception e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
        context.close();
        Platform.exit();
    }
}