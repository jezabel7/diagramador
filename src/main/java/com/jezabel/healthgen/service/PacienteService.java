package com.jezabel.healthgen.service;

import com.jezabel.healthgen.dto.PacienteDTO;
import java.util.List;

public interface PacienteService {
    PacienteDTO crear(PacienteDTO dto);
    PacienteDTO obtenerPorId(Long id);
    List<PacienteDTO> listar();
    PacienteDTO actualizar(Long id, PacienteDTO dto);
    void eliminar(Long id);
}
