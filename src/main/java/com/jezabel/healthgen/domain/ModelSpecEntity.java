package com.jezabel.healthgen.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "model_spec")
public class ModelSpecEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String version;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String json;  // guardamos el JSON completo

    private Instant createdAt;

    public ModelSpecEntity() {
    }

    public ModelSpecEntity(Long id, String name, String version, String json, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.json = json;
        this.createdAt = createdAt;
    }

    // Getters y Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getJson() { return json; }
    public void setJson(String json) { this.json = json; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
