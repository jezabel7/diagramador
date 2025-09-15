package com.jezabel.healthgen.service;

import com.jezabel.healthgen.dto.PacienteDTO;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PacienteService {
    PacienteDTO crear(PacienteDTO dto);
    PacienteDTO obtenerPorId(Long id);
    List<PacienteDTO> listar();
    PacienteDTO actualizar(Long id, PacienteDTO dto);
    void eliminar(Long id);
    Page<PacienteDTO> listarPaginado(String q, Pageable pageable);

}
