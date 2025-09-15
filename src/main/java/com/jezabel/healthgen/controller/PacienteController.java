package com.jezabel.healthgen.controller;

import com.jezabel.healthgen.dto.PacienteDTO;
import com.jezabel.healthgen.service.PacienteService;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/pacientes")
public class PacienteController {

    private final PacienteService service;
    public PacienteController(PacienteService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PacienteDTO> crear(@Valid @RequestBody PacienteDTO dto) {
        PacienteDTO creado = service.crear(dto);
        return ResponseEntity.created(URI.create("/api/pacientes/" + creado.getId())).body(creado);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PacienteDTO> obtener(@PathVariable Long id) {
        return ResponseEntity.ok(service.obtenerPorId(id));
    }

    @GetMapping
    public ResponseEntity<List<PacienteDTO>> listar() {
        return ResponseEntity.ok(service.listar());
    }

    @PutMapping("/{id}")
    public ResponseEntity<PacienteDTO> actualizar(@PathVariable Long id, @Valid @RequestBody PacienteDTO dto) {
        return ResponseEntity.ok(service.actualizar(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        service.eliminar(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/page")
    public ResponseEntity<Page<PacienteDTO>> listarPaginado(
            @RequestParam(required = false, defaultValue = "") String q,
            Pageable pageable
    ) {
        return ResponseEntity.ok(service.listarPaginado(q, pageable));
    }
}
