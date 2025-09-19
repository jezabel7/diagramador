package com.jezabel.healthgen.controller;

import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class GraphWsController {
    private final SimpMessagingTemplate bus;
    public GraphWsController(SimpMessagingTemplate bus){ this.bus = bus; }

    // Recibe un "patch" del grafo y lo reenvía al topic del documento
    @MessageMapping("/graph.update.{docId}")
    public void update(@DestinationVariable String docId, @Payload String patchJson) {
        bus.convertAndSend("/topic/graph."+docId, patchJson);
    }
}
