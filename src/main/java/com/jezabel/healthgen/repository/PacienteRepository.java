package com.jezabel.healthgen.repository;

import com.jezabel.healthgen.domain.Paciente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PacienteRepository extends JpaRepository<Paciente, Long> {
    Optional<Paciente> findByDocumento(String documento);
    boolean existsByDocumento(String documento);
}

