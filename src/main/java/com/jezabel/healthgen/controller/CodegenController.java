package com.jezabel.healthgen.controller;

import com.jezabel.healthgen.domain.ModelSpecEntity;
import com.jezabel.healthgen.repository.ModelSpecRepository;
import com.jezabel.healthgen.service.CodegenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.nio.file.Path;   // 👈 ESTE IMPORT
import java.util.Map;

@RestController
@RequestMapping("/api/codegen")
public class CodegenController {

    private final ModelSpecRepository specRepo;
    private final CodegenService codegen;

    public CodegenController(ModelSpecRepository specRepo, CodegenService codegen) {
        this.specRepo = specRepo;
        this.codegen = codegen;
    }

    // GET /api/codegen/{id}/entities -> genera SOLO Entities en carpeta temporal
    @GetMapping("/{id}/entities")
    public ResponseEntity<Map<String, Object>> generateEntities(@PathVariable Long id) throws Exception {
        ModelSpecEntity spec = specRepo.findById(id).orElseThrow();
        Map<String, Object> result = codegen.generateEntities(spec);
        return ResponseEntity.ok(result);
    }

    // GET /api/codegen/{id}/zip -> genera proyecto completo en un ZIP descargable
    @GetMapping("/{id}/zip")
    public ResponseEntity<Resource> generateZip(@PathVariable Long id) throws Exception {
        ModelSpecEntity spec = specRepo.findById(id).orElseThrow();
        Path zipFile = codegen.generateZip(spec);

        Resource resource = new FileSystemResource(zipFile);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + spec.getName() + ".zip")
                .contentLength(zipFile.toFile().length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
