package com.jezabel.healthgen.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.domain.ModelSpecEntity;
import com.jezabel.healthgen.repository.ModelSpecRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GenerateController {

    private final ModelSpecRepository repo;
    private final ObjectMapper objectMapper;

    // Constructor manual (sin Lombok)
    public GenerateController(ModelSpecRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // POST /api/generate -> guarda el JSON del modelo
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> saveSpec(@RequestBody Map<String, Object> spec) {
        String name = (String) spec.getOrDefault("name", "UnnamedModel");
        String version = (String) spec.getOrDefault("version", "0.0.1");
        String json;
        try {
            json = objectMapper.writeValueAsString(spec); // convertimos el Map al JSON crudo
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "JSON inválido: " + e.getMessage()
            ));
        }

        ModelSpecEntity entity = new ModelSpecEntity(null, name, version, json, Instant.now());
        entity = repo.save(entity);

        return ResponseEntity.ok(Map.of(
                "id", entity.getId(),
                "name", entity.getName(),
                "version", entity.getVersion(),
                "status", "SAVED"
        ));
    }
}
