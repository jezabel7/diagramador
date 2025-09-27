package com.jezabel.healthgen.controller;

import com.jezabel.healthgen.ai.GeminiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiTestController {

    private final GeminiClient gemini;

    public AiTestController(GeminiClient gemini) {
        this.gemini = gemini;
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody Map<String, Object> body) throws Exception {
        String prompt = String.valueOf(body.getOrDefault("prompt", "Di 'hola mundo' en una frase"));
        String text = gemini.generateText(prompt);
        return ResponseEntity.ok(Map.of("text", text));
    }
}
