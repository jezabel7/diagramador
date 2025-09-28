package com.jezabel.healthgen.controller;

import com.jezabel.healthgen.service.AiDiagramService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/ai")
public class AiDiagramController {

    private final AiDiagramService svc;

    public AiDiagramController(AiDiagramService svc) {
        this.svc = svc;
    }

    @PostMapping(value="/diagram", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> diagram(@RequestBody Map<String,Object> body) {
        String prompt = Objects.toString(body.get("prompt"), "");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt requerido");
        return svc.generateModelSpecFromPrompt(prompt);
    }
}
