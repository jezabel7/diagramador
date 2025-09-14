package com.jezabel.healthgen.domain;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

@Entity
@Table(name = "pacientes")
public class Paciente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 80, message = "Nombre demasiado largo")
    @Column(nullable = false, length = 80)
    private String nombre;

    @NotBlank(message = "El apellido es obligatorio")
    @Size(max = 80, message = "Apellido demasiado largo")
    @Column(nullable = false, length = 80)
    private String apellido;

    @NotBlank(message = "El documento es obligatorio")
    @Size(max = 30, message = "Documento demasiado largo")
    @Column(nullable = false, length = 30, unique = true)
    private String documento; // CI/Pasaporte

    @Past(message = "La fecha de nacimiento debe ser en el pasado")
    private LocalDate fechaNacimiento;

    @Email(message = "Email inválido")
    @Size(max = 120, message = "Email demasiado largo")
    @Column(length = 120)
    private String email;

    @Size(max = 30, message = "Teléfono demasiado largo")
    @Column(length = 30)
    private String telefono;

    // Getters y Setters (sin Lombok para que sea claro)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getApellido() { return apellido; }
    public void setApellido(String apellido) { this.apellido = apellido; }

    public String getDocumento() { return documento; }
    public void setDocumento(String documento) { this.documento = documento; }

    public LocalDate getFechaNacimiento() { return fechaNacimiento; }
    public void setFechaNacimiento(LocalDate fechaNacimiento) { this.fechaNacimiento = fechaNacimiento; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
}