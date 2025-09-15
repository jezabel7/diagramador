package com.jezabel.healthgen.repository;

import com.jezabel.healthgen.domain.Paciente;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PacienteRepository extends JpaRepository<Paciente, Long> {

    Optional<Paciente> findByDocumento(String documento);
    boolean existsByDocumento(String documento);

    Page<Paciente> findByNombreContainingIgnoreCaseOrApellidoContainingIgnoreCaseOrDocumentoContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String q1, String q2, String q3, String q4, Pageable pageable
    );
}
