package com.jezabel.healthgen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.service.AiDocService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/docs")
public class AiDocsController {

    private final AiDocService service;
    private final ObjectMapper om;

    public AiDocsController(AiDocService service, ObjectMapper om) {
        this.service = service;
        this.om = om;
    }

    // Body puede ser { "id": 123 }  o  { "spec": { ...modelSpec... }, "filename":"opcional.pdf" }
    @PostMapping
    public ResponseEntity<byte[]> generate(@RequestBody Map<String,Object> body) throws Exception {
        byte[] pdf;
        String filename = String.valueOf(body.getOrDefault("filename", "documentacion.pdf"));

        if (body.containsKey("id")) {
            Long id = Long.valueOf(String.valueOf(body.get("id")));
            pdf = service.generatePdfFromId(id);
        } else if (body.containsKey("spec")) {
            @SuppressWarnings("unchecked") Map<String,Object> spec = (Map<String,Object>) body.get("spec");
            pdf = service.generatePdfFromSpec(spec);
        } else {
            return ResponseEntity.badRequest().body(null);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}
