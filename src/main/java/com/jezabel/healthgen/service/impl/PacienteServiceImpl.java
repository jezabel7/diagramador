package com.jezabel.healthgen.service.impl;

import com.jezabel.healthgen.domain.Paciente;
import com.jezabel.healthgen.dto.PacienteDTO;
import com.jezabel.healthgen.exception.ResourceNotFoundException;
import com.jezabel.healthgen.repository.PacienteRepository;
import com.jezabel.healthgen.service.PacienteService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class PacienteServiceImpl implements PacienteService {

    private final PacienteRepository repo;
    public PacienteServiceImpl(PacienteRepository repo) { this.repo = repo; }

    @Override
    public PacienteDTO crear(PacienteDTO dto) {
        if (repo.existsByDocumento(dto.getDocumento())) {
            throw new IllegalArgumentException("Ya existe un paciente con ese documento");
        }
        Paciente p = toEntity(dto);
        p.setId(null);
        Paciente guardado = repo.save(p);
        return toDTO(guardado);
    }

    @Override
    @Transactional(readOnly = true)
    public PacienteDTO obtenerPorId(Long id) {
        Paciente p = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
        return toDTO(p);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PacienteDTO> listar() {
        return repo.findAll().stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public PacienteDTO actualizar(Long id, PacienteDTO dto) {
        Paciente p = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
        // Si cambia documento, validar duplicado
        if (dto.getDocumento() != null && !dto.getDocumento().equals(p.getDocumento())
                && repo.existsByDocumento(dto.getDocumento())) {
            throw new IllegalArgumentException("Documento ya en uso por otro paciente");
        }
        p.setNombre(dto.getNombre());
        p.setApellido(dto.getApellido());
        p.setDocumento(dto.getDocumento());
        p.setFechaNacimiento(dto.getFechaNacimiento());
        p.setEmail(dto.getEmail());
        p.setTelefono(dto.getTelefono());
        return toDTO(repo.save(p));
    }

    @Override
    public void eliminar(Long id) {
        if (!repo.existsById(id)) {
            throw new ResourceNotFoundException("Paciente no encontrado");
        }
        repo.deleteById(id);
    }

    private PacienteDTO toDTO(Paciente p) {
        PacienteDTO dto = new PacienteDTO();
        dto.setId(p.getId());
        dto.setNombre(p.getNombre());
        dto.setApellido(p.getApellido());
        dto.setDocumento(p.getDocumento());
        dto.setFechaNacimiento(p.getFechaNacimiento());
        dto.setEmail(p.getEmail());
        dto.setTelefono(p.getTelefono());
        return dto;
    }

    private Paciente toEntity(PacienteDTO dto) {
        Paciente p = new Paciente();
        p.setNombre(dto.getNombre());
        p.setApellido(dto.getApellido());
        p.setDocumento(dto.getDocumento());
        p.setFechaNacimiento(dto.getFechaNacimiento());
        p.setEmail(dto.getEmail());
        p.setTelefono(dto.getTelefono());
        return p;
    }
}