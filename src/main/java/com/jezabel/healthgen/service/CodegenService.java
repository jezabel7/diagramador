package com.jezabel.healthgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.codegen.TypeMapper;
import com.jezabel.healthgen.domain.ModelSpecEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.UncheckedIOException;

/**
 * Generador: Entities + Repository + Service + Controller + pom + Application + properties
 * AHORA con relaciones JPA según multiplicidades del ModelSpec.
 */
@Service
public class CodegenService {

    private final ObjectMapper objectMapper;

    public CodegenService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path generateZip(ModelSpecEntity specEntity) throws IOException {
        Map<String, Object> result = generateEntities(specEntity);
        String tmpDir = (String) result.get("tmpDir");

        Path root = Path.of(tmpDir);
        Path zipFile = Files.createTempFile("healthgen-", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String entryName = root.relativize(path).toString().replace("\\", "/");
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return zipFile;
    }

    // ===== Tipos de relación y cardinalidad =====

    enum RelKind { ASSOCIATION, AGGREGATION, COMPOSITION, GENERALIZATION, DEPENDENCY, REALIZATION, UNKNOWN }
    enum Card { ONE, MANY }

    static class Attr {
        String name;
        String type;   // STRING, INT, LONG...
        boolean pk;
        String generated; // e.g. IDENTITY
    }

    static class Rel {
        RelKind kind;
        String source;
        String target;
        String multSource;  // "1", "0..1", "0..*", "1..*", "*", etc
        String multTarget;
    }

    static class EntitySpec {
        String name;
        List<Attr> attrs = new ArrayList<>();
    }

    /** Genera proyecto completo. */
    public Map<String, Object> generateEntities(ModelSpecEntity specEntity) throws IOException {
        Map<String, Object> spec = objectMapper.readValue(
                specEntity.getJson(), new TypeReference<Map<String, Object>>() {});

        String packageBase = (String) spec.getOrDefault("packageBase", "com.example.demo");
        String artifactId = (String) spec.getOrDefault("name", "generated-app");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawEntities =
                (List<Map<String, Object>>) spec.getOrDefault("entities", List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRelations =
                (List<Map<String, Object>>) spec.getOrDefault("relations", List.of());

        // Parse entidades
        Map<String, EntitySpec> entities = new LinkedHashMap<>();
        for (Map<String, Object> e : rawEntities) {
            String entityName = (String) e.get("name");
            if (entityName == null || entityName.isBlank()) continue;

            EntitySpec es = new EntitySpec();
            es.name = entityName;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attrs =
                    (List<Map<String, Object>>) e.getOrDefault("attributes", List.of());
            for (Map<String, Object> a : attrs) {
                Attr at = new Attr();
                at.name = (String) a.get("name");
                at.type = (String) a.get("type");
                at.pk = Boolean.TRUE.equals(a.get("pk"));
                Object gen = a.get("generated");
                at.generated = gen == null ? null : gen.toString();
                if (at.name != null && !at.name.isBlank()) {
                    es.attrs.add(at);
                }
            }
            entities.put(es.name, es);
        }

        // Parse relaciones
        List<Rel> rels = new ArrayList<>();
        for (Map<String, Object> r : rawRelations) {
            Rel rel = new Rel();
            rel.kind = toRelKind((String) r.get("type"));
            rel.source = (String) r.get("source");
            rel.target = (String) r.get("target");
            rel.multSource = optString(r.get("multSource"));
            rel.multTarget = optString(r.get("multTarget"));
            // Solo usamos Association/Aggregation/Composition para JPA
            if (rel.source != null && rel.target != null) {
                rels.add(rel);
            }
        }

        // FS de salida
        Path root = Files.createTempDirectory("healthgen-");
        Path srcMainJava = root.resolve("src/main/java/" + packageBase.replace('.', '/'));
        Path domainDir = srcMainJava.resolve("domain");
        Files.createDirectories(domainDir);

        List<String> createdFiles = new ArrayList<>();

        // Render de entidades
        for (EntitySpec es : entities.values()) {
            String code = renderEntity(packageBase, es, rels, entities);
            Path target = domainDir.resolve(es.name + ".java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, code, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(target).toString().replace("\\", "/"));

            // Repository
            Path repoFile = srcMainJava.resolve("repository/" + es.name + "Repository.java");
            Files.createDirectories(repoFile.getParent());
            Files.writeString(repoFile, renderRepository(packageBase, es.name),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(repoFile).toString().replace("\\","/"));

            // Service
            Path serviceFile = srcMainJava.resolve("service/" + es.name + "Service.java");
            Files.createDirectories(serviceFile.getParent());
            Files.writeString(serviceFile, renderService(packageBase, es.name),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(serviceFile).toString().replace("\\","/"));

            // Controller
            Path controllerFile = srcMainJava.resolve("controller/" + es.name + "Controller.java");
            Files.createDirectories(controllerFile.getParent());
            Files.writeString(controllerFile, renderController(packageBase, es.name, es.attrs),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(controllerFile).toString().replace("\\","/"));


            // DTO
            Path dtoFile = srcMainJava.resolve("dto/" + es.name + "DTO.java");
            Files.createDirectories(dtoFile.getParent());
            Files.writeString(dtoFile, renderDto(packageBase, es, rels), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(dtoFile).toString().replace("\\","/"));

            // Mapper
            Path mapperFile = srcMainJava.resolve("mapper/" + es.name + "Mapper.java");
            Files.createDirectories(mapperFile.getParent());
            Files.writeString(mapperFile, renderMapper(packageBase, es, rels), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(mapperFile).toString().replace("\\","/"));


        }

        // Extras
        Path pomFile = root.resolve("pom.xml");
        Files.writeString(pomFile, renderPom(packageBase, artifactId),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        createdFiles.add(root.relativize(pomFile).toString());

        Path appFile = srcMainJava.resolve("Application.java");
        Files.createDirectories(appFile.getParent());
        Files.writeString(appFile, renderApplication(packageBase),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        createdFiles.add(root.relativize(appFile).toString().replace("\\","/"));

        Path resDir = root.resolve("src/main/resources");
        Files.createDirectories(resDir);
        Path propsFile = resDir.resolve("application.properties");
        Files.writeString(propsFile, renderProperties(artifactId),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        createdFiles.add(root.relativize(propsFile).toString().replace("\\","/"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tmpDir", root.toString());
        result.put("packageBase", packageBase);
        result.put("artifactId", artifactId);
        result.put("files", createdFiles);
        return result;
    }

    // ===================== RENDER: ENTITY con relaciones =====================

    private String renderEntity(String packageBase, EntitySpec es, List<Rel> allRels, Map<String, EntitySpec> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageBase).append(".domain;\n\n")
                .append("import jakarta.persistence.*;\n")
                .append("import java.io.Serializable;\n")
                .append("import java.util.*;\n")
                .append("import com.fasterxml.jackson.annotation.*;\n\n")
                .append("@Entity\n")
                .append("@Table(name = \"").append(toTableName(es.name)).append("\")\n")
                .append("@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = \"id\")\n")
                .append("public class ").append(es.name).append(" implements Serializable {\n\n");

        // ===== Campos "simples" =====
        for (Attr a : es.attrs) {
            String name = a.name;
            if (name == null || name.isBlank()) continue;
            String type = TypeMapper.toJavaType(a.type);
            if (a.pk) {
                sb.append("    @Id\n");
                if ("IDENTITY".equalsIgnoreCase(a.generated)) {
                    sb.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
                }
            }
            sb.append("    @Column(name = \"").append(name).append("\")\n");
            sb.append("    private ").append(type).append(" ").append(name).append(";\n\n");
        }

        // ===== Campos de RELACIONES JPA =====
        // Filtra relaciones donde participa esta entidad
        List<Rel> relsHere = new ArrayList<>();
        for (Rel r : allRels) {
            if (es.name.equals(r.source) || es.name.equals(r.target)) {
                relsHere.add(r);
            }
        }

        // Mantener nombres ya usados para no chocar
        Set<String> usedNames = new HashSet<>();
        for (Attr a : es.attrs) usedNames.add(a.name);

        for (Rel r : relsHere) {
            RelKind kind = r.kind;
            if (!(kind == RelKind.ASSOCIATION || kind == RelKind.AGGREGATION || kind == RelKind.COMPOSITION)) {
                // Generalization/Dependency/Realization: por ahora no generan campos
                continue;
            }

            boolean self = r.source.equals(r.target);
            Card[] cc = cardsFor(r);
            Card cSrc = cc[0];
            Card cTgt = cc[1];

            String otherName;
            boolean iAmSource = es.name.equals(r.source);
            if (iAmSource) otherName = r.target; else otherName = r.source;

            // Nombres deterministas
            String me = es.name;
            String other = otherName;
            String meLower = lower(me);
            String otherLower = lower(other);
            String otherPlural = plural(otherLower);

            boolean dashedIsComposition = (kind == RelKind.COMPOSITION);
            boolean dashedIsAggregation = (kind == RelKind.AGGREGATION);

            // OWNER para 1-1 y *-*
            String ownerByLex = (me.compareTo(other) <= 0) ? me : other;

            // SELF: 1-* → parent/children
            if (self && ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE))) {
                // Campo ManyToOne: parent
                sb.append("    @ManyToOne");
                if (dashedIsComposition) sb.append("(cascade = CascadeType.ALL)");
                sb.append("\n");
                sb.append("    @JoinColumn(name = \"parent_id\")\n");
                sb.append("    private ").append(me).append(" parent;\n\n");
                usedNames.add("parent");

                // Campo OneToMany: children
                sb.append("    @OneToMany(mappedBy = \"parent\"");
                if (dashedIsComposition) sb.append(", cascade = CascadeType.ALL, orphanRemoval = true");
                sb.append(")\n");
                sb.append("    private Set<").append(me).append("> children = new HashSet<>();\n\n");
                usedNames.add("children");
                continue;
            }

            // Association/Aggregation/Composition no-self:
            if ( (cSrc == Card.ONE && cTgt == Card.ONE) ) {
                // OneToOne: dueño = lexicográficamente menor
                boolean iAmOwner = me.equals(ownerByLex);
                String fieldName = safeVar(otherLower, usedNames);

                if (iAmOwner) {
                    sb.append("    @OneToOne\n");
                    sb.append("    @JoinColumn(name = \"").append(otherLower).append("_id\")\n");
                    sb.append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                } else {
                    String mappedBy = lower(other) ; // en el owner, su campo hacia "otro"
                    sb.append("    @OneToOne(mappedBy = \"").append(mappedBy).append("\")\n");
                    sb.append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                }
                usedNames.add(fieldName);

            } else if ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE)) {
                // OneToMany / ManyToOne
                // El lado MANY tiene ManyToOne con JoinColumn hacia el lado ONE
                boolean iAmManySide = (iAmSource && cSrc == Card.MANY) || (!iAmSource && cTgt == Card.MANY);
                if (iAmManySide) {
                    String fieldName = safeVar(otherLower, usedNames);
                    sb.append("    @ManyToOne\n");
                    sb.append("    @JoinColumn(name = \"").append(otherLower).append("_id\")\n");
                    sb.append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                    usedNames.add(fieldName);
                } else {
                    // I'm ONE side: genero colección
                    String fieldName = safeVar(plural(otherLower), usedNames);
                    sb.append("    @OneToMany(mappedBy = \"").append(lower(me)).append("\"");
                    if (dashedIsComposition) {
                        sb.append(", cascade = CascadeType.ALL, orphanRemoval = true");
                    } else if (dashedIsAggregation) {
                        // puedes ajustar cascadas para agregación si quieres
                    }
                    sb.append(")\n");
                    sb.append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                    usedNames.add(fieldName);
                }

            } else {
                // ManyToMany
                boolean iAmOwner = me.equals(ownerByLex);
                String fieldName = safeVar(plural(otherLower), usedNames);
                if (iAmOwner) {
                    String jt = (meLower + "_" + otherLower);
                    sb.append("    @ManyToMany\n");
                    sb.append("    @JoinTable(name = \"").append(jt).append("\",\n");
                    sb.append("        joinColumns = @JoinColumn(name = \"").append(meLower).append("_id\"),\n");
                    sb.append("        inverseJoinColumns = @JoinColumn(name = \"").append(otherLower).append("_id\"))\n");
                    sb.append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                } else {
                    // inverso
                    String mappedBy = plural(lower(ownerByLex.equals(me) ? other : ownerByLex));
                    sb.append("    @ManyToMany(mappedBy = \"").append(mappedBy).append("\")\n");
                    sb.append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                }
                usedNames.add(fieldName);
            }
        }

        // ===== Getters/Setters =====
        for (Attr a : es.attrs) {
            String name = a.name;
            if (name == null || name.isBlank()) continue;
            String type = TypeMapper.toJavaType(a.type);
            String Cap = cap(name);
            sb.append("    public ").append(type).append(" get").append(Cap).append("() { return ").append(name).append("; }\n");
            sb.append("    public void set").append(Cap).append("(").append(type).append(" ").append(name).append(") { this.")
                    .append(name).append(" = ").append(name).append("; }\n\n");
        }

        // Getters/Setters para relaciones: escanear campos añadidos (heurística simple)
        // (En un generador real, convendría registrar los nombres. Para mantener simple,
        //  generamos getters/setters para Set<> y para referencias directas conocidas.)
        // Aquí omitimos reflexión; en proyectos reales, usa un motor de plantillas.

        sb.append("}\n");
        return sb.toString();
    }

    // ===================== Resto de plantillas =====================

    private String renderRepository(String packageBase, String entityName) {
        return "package " + packageBase + ".repository;\n\n" +
                "import " + packageBase + ".domain." + entityName + ";\n" +
                "import org.springframework.data.jpa.repository.JpaRepository;\n\n" +
                "public interface " + entityName + "Repository extends JpaRepository<" + entityName + ", Long> {\n}\n";
    }

    private String renderService(String packageBase, String entityName) {
        return "package " + packageBase + ".service;\n\n" +
                "import " + packageBase + ".domain." + entityName + ";\n" +
                "import " + packageBase + ".repository." + entityName + "Repository;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "import java.util.*;\n\n" +
                "@Service\n" +
                "public class " + entityName + "Service {\n" +
                "    private final " + entityName + "Repository repo;\n\n" +
                "    public " + entityName + "Service(" + entityName + "Repository repo) { this.repo = repo; }\n\n" +
                "    public " + entityName + " save(" + entityName + " e) { return repo.save(e); }\n" +
                "    public Optional<" + entityName + "> findById(Long id) { return repo.findById(id); }\n" +
                "    public List<" + entityName + "> findAll() { return repo.findAll(); }\n" +
                "    public void delete(Long id) { repo.deleteById(id); }\n" +
                "}\n";
    }

    private String renderController(String packageBase, String entityName, List<Attr> attrs) {
        String var = entityName.substring(0,1).toLowerCase() + entityName.substring(1);
        String dto = entityName + "DTO";
        String mapper = entityName + "Mapper";

        return ""
                + "package " + packageBase + ".controller;\n\n"
                + "import " + packageBase + ".domain." + entityName + ";\n"
                + "import " + packageBase + ".dto." + dto + ";\n"
                + "import " + packageBase + ".mapper." + mapper + ";\n"
                + "import " + packageBase + ".service." + entityName + "Service;\n"
                + "import org.springframework.http.ResponseEntity;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "import java.net.URI;\n"
                + "import java.util.*;\n\n"
                + "@RestController\n"
                + "@RequestMapping(\"/api/" + var + "s\")\n"
                + "public class " + entityName + "Controller {\n"
                + "    private final " + entityName + "Service service;\n"
                + "    private final " + mapper + " mapper;\n\n"
                + "    public " + entityName + "Controller(" + entityName + "Service service, " + mapper + " mapper) {\n"
                + "        this.service = service; this.mapper = mapper;\n"
                + "    }\n\n"
                + "    @PostMapping\n"
                + "    public ResponseEntity<" + dto + "> create(@RequestBody " + dto + " body){\n"
                + "        " + entityName + " saved = service.save(mapper.toEntity(body));\n"
                + "        return ResponseEntity.created(URI.create(\"/api/" + var + "s/\" + saved.getId())).body(mapper.toDto(saved));\n"
                + "    }\n\n"
                + "    @GetMapping\n"
                + "    public List<" + dto + "> all(){\n"
                + "        List<" + entityName + "> list = service.findAll();\n"
                + "        List<" + dto + "> out = new ArrayList<>();\n"
                + "        for (" + entityName + " e : list) out.add(mapper.toDto(e));\n"
                + "        return out;\n"
                + "    }\n\n"
                + "    @GetMapping(\"/{id}\")\n"
                + "    public " + dto + " one(@PathVariable Long id){\n"
                + "        return mapper.toDto(service.findById(id).orElseThrow());\n"
                + "    }\n\n"
                + "    @PutMapping(\"/{id}\")\n"
                + "    public " + dto + " update(@PathVariable Long id, @RequestBody " + dto + " body){\n"
                + "        " + entityName + " existing = service.findById(id).orElseThrow();\n"
                + "        " + entityName + " incoming = mapper.toEntity(body);\n"
                + "        incoming.setId(existing.getId());\n"
                + "        return mapper.toDto(service.save(incoming));\n"
                + "    }\n\n"
                + "    @DeleteMapping(\"/{id}\")\n"
                + "    public ResponseEntity<Void> delete(@PathVariable Long id){\n"
                + "        service.delete(id);\n"
                + "        return ResponseEntity.noContent().build();\n"
                + "    }\n"
                + "}\n";
    }


    private String renderDto(String packageBase, EntitySpec es, List<Rel> allRels) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageBase).append(".dto;\n\n")
                .append("import java.util.*;\n\n")
                .append("public class ").append(es.name).append("DTO {\n\n");

        // atributos simples
        for (Attr a : es.attrs) {
            String type = TypeMapper.toJavaType(a.type);
            sb.append("    private ").append(type).append(" ").append(a.name).append(";\n");
        }

        // relaciones → ids
        Set<String> used = new HashSet<>();
        for (Rel r : allRels) {
            if (!(es.name.equals(r.source) || es.name.equals(r.target))) continue;

            RelKind kind = r.kind;
            if (!(kind == RelKind.ASSOCIATION || kind == RelKind.AGGREGATION || kind == RelKind.COMPOSITION)) continue;

            Card[] cc = cardsFor(r);
            Card cSrc = cc[0], cTgt = cc[1];

            boolean iAmSource = es.name.equals(r.source);
            String other = iAmSource ? r.target : r.source;
            String otherLower = lower(other);
            String othersLower = plural(otherLower);

            // self 1-* → parent/children
            if (r.source.equals(r.target) && ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE))) {
                if (!used.contains("parentId")) {
                    sb.append("    private Long parentId;\n");
                    used.add("parentId");
                }
                if (!used.contains("childrenIds")) {
                    sb.append("    private Set<Long> childrenIds;\n");
                    used.add("childrenIds");
                }
                continue;
            }

            // 1-1
            if (cSrc == Card.ONE && cTgt == Card.ONE) {
                String f = otherLower + "Id";
                if (!used.contains(f)) {
                    sb.append("    private Long ").append(f).append(";\n");
                    used.add(f);
                }
                continue;
            }

            // 1-* / *-1
            if ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE)) {
                boolean iAmMany = (iAmSource && cSrc == Card.MANY) || (!iAmSource && cTgt == Card.MANY);
                if (iAmMany) {
                    String f = otherLower + "Id";
                    if (!used.contains(f)) { sb.append("    private Long ").append(f).append(";\n"); used.add(f); }
                } else {
                    String f = othersLower + "Ids";
                    if (!used.contains(f)) { sb.append("    private Set<Long> ").append(f).append(";\n"); used.add(f); }
                }
                continue;
            }

            // *-*
            String f = othersLower + "Ids";
            if (!used.contains(f)) { sb.append("    private Set<Long> ").append(f).append(";\n"); used.add(f); }
        }

        // getters/setters
        for (Attr a : es.attrs) {
            String type = TypeMapper.toJavaType(a.type);
            String Cap = cap(a.name);
            sb.append("    public ").append(type).append(" get").append(Cap).append("() { return ").append(a.name).append("; }\n");
            sb.append("    public void set").append(Cap).append("(").append(type).append(" ").append(a.name).append(") { this.").append(a.name).append(" = ").append(a.name).append("; }\n");
        }
        for (String f : used) {
            String Cap = cap(f);
            String t = f.endsWith("Ids") ? "Set<Long>" : "Long";
            sb.append("    public ").append(t).append(" get").append(Cap).append("() { return ").append(f).append("; }\n");
            sb.append("    public void set").append(Cap).append("(").append(t).append(" ").append(f).append(") { this.").append(f).append(" = ").append(f).append("; }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String renderMapper(String packageBase, EntitySpec es, List<Rel> allRels) {
        String e = es.name;
        String dto = e + "DTO";
        StringBuilder m = new StringBuilder();
        m.append("package ").append(packageBase).append(".mapper;\n\n")
                .append("import org.mapstruct.*;\n")
                .append("import java.util.*;\n")
                .append("import ").append(packageBase).append(".domain.").append(e).append(";\n")
                .append("import ").append(packageBase).append(".dto.").append(dto).append(";\n");

        // imports de otras entidades referenciadas
        Set<String> others = new LinkedHashSet<>();
        for (Rel r : allRels) {
            if (!(e.equals(r.source) || e.equals(r.target))) continue;
            String o = e.equals(r.source) ? r.target : r.source;
            if (!o.equals(e)) others.add(o);
        }
        for (String o : others) {
            m.append("import ").append(packageBase).append(".domain.").append(o).append(";\n");
        }
        m.append("\n@Mapper(componentModel = \"spring\")\n")
                .append("public interface ").append(e).append("Mapper {\n\n");

        // ---- toDto mapping annotations ----
        List<String> toDtoMaps = new ArrayList<>();
        for (Rel r : allRels) {
            if (!(e.equals(r.source) || e.equals(r.target))) continue;
            RelKind kind = r.kind;
            if (!(kind == RelKind.ASSOCIATION || kind == RelKind.AGGREGATION || kind == RelKind.COMPOSITION)) continue;

            Card[] cc = cardsFor(r);
            Card cSrc = cc[0], cTgt = cc[1];
            boolean iAmSource = e.equals(r.source);
            String other = iAmSource ? r.target : r.source;
            String otherLower = lower(other);
            String othersLower = plural(otherLower);

            if (r.source.equals(r.target) && ((cSrc==Card.ONE && cTgt==Card.MANY) || (cSrc==Card.MANY && cTgt==Card.ONE))) {
                toDtoMaps.add("@Mapping(target = \"parentId\", source = \"parent.id\")");
                toDtoMaps.add("@Mapping(target = \"childrenIds\", source = \"children\")");
                continue;
            }
            if (cSrc == Card.ONE && cTgt == Card.ONE) {
                toDtoMaps.add("@Mapping(target = \"" + otherLower + "Id\", source = \"" + otherLower + ".id\")");
                continue;
            }
            if ((cSrc==Card.ONE && cTgt==Card.MANY) || (cSrc==Card.MANY && cTgt==Card.ONE)) {
                boolean iAmMany = (iAmSource && cSrc==Card.MANY) || (!iAmSource && cTgt==Card.MANY);
                if (iAmMany) {
                    toDtoMaps.add("@Mapping(target = \"" + otherLower + "Id\", source = \"" + otherLower + ".id\")");
                } else {
                    toDtoMaps.add("@Mapping(target = \"" + othersLower + "Ids\", source = \"" + othersLower + "\")");
                }
                continue;
            }
            // *-*
            toDtoMaps.add("@Mapping(target = \"" + othersLower + "Ids\", source = \"" + othersLower + "\")");
        }

        for (String ann : toDtoMaps) m.append("    ").append(ann).append("\n");
        m.append("    ").append(dto).append(" toDto(").append(e).append(" entity);\n\n");

        // ---- toEntity mapping annotations ----
        List<String> toEntityMaps = new ArrayList<>();
        for (Rel r : allRels) {
            if (!(e.equals(r.source) || e.equals(r.target))) continue;
            RelKind kind = r.kind;
            if (!(kind == RelKind.ASSOCIATION || kind == RelKind.AGGREGATION || kind == RelKind.COMPOSITION)) continue;

            Card[] cc = cardsFor(r);
            Card cSrc = cc[0], cTgt = cc[1];
            boolean iAmSource = e.equals(r.source);
            String other = iAmSource ? r.target : r.source;
            String otherLower = lower(other);
            String othersLower = plural(otherLower);

            if (r.source.equals(r.target) && ((cSrc==Card.ONE && cTgt==Card.MANY) || (cSrc==Card.MANY && cTgt==Card.ONE))) {
                toEntityMaps.add("@Mapping(target = \"parent\", source = \"parentId\")");
                toEntityMaps.add("@Mapping(target = \"children\", source = \"childrenIds\")");
                continue;
            }
            if (cSrc == Card.ONE && cTgt == Card.ONE) {
                toEntityMaps.add("@Mapping(target = \"" + otherLower + "\", source = \"" + otherLower + "Id\")");
                continue;
            }
            if ((cSrc==Card.ONE && cTgt==Card.MANY) || (cSrc==Card.MANY && cTgt==Card.ONE)) {
                boolean iAmMany = (iAmSource && cSrc==Card.MANY) || (!iAmSource && cTgt==Card.MANY);
                if (iAmMany) {
                    toEntityMaps.add("@Mapping(target = \"" + otherLower + "\", source = \"" + otherLower + "Id\")");
                } else {
                    toEntityMaps.add("@Mapping(target = \"" + othersLower + "\", source = \"" + othersLower + "Ids\")");
                }
                continue;
            }
            // *-*
            toEntityMaps.add("@Mapping(target = \"" + othersLower + "\", source = \"" + othersLower + "Ids\")");
        }

        for (String ann : toEntityMaps) m.append("    ").append(ann).append("\n");
        m.append("    ").append(e).append(" toEntity(").append(dto).append(" dto);\n\n");

        // helpers: ids <-> entities (sin hits a DB; crea referencias solo con id)
        for (String o : others) {
            String oLower = lower(o);
            m.append("    default ").append(o).append(" ").append(oLower).append("FromId(Long id) {\n")
                    .append("        if (id == null) return null;\n")
                    .append("        ").append(o).append(" x = new ").append(o).append("();\n")
                    .append("        x.setId(id);\n")
                    .append("        return x;\n")
                    .append("    }\n")
                    .append("    default Set<").append(o).append("> ").append(oLower).append("sFromIds(Set<Long> ids) {\n")
                    .append("        if (ids == null) return null;\n")
                    .append("        Set<").append(o).append("> out = new HashSet<>();\n")
                    .append("        for (Long id : ids) { ").append(o).append(" x = ").append(oLower).append("FromId(id); if (x!=null) out.add(x);} \n")
                    .append("        return out;\n")
                    .append("    }\n")
                    .append("    default Long ").append(oLower).append("ToId(").append(o).append(" x) { return x==null?null:x.getId(); }\n")
                    .append("    default Set<Long> ").append(oLower).append("sToIds(Set<").append(o).append("> xs) {\n")
                    .append("        if (xs == null) return null;\n")
                    .append("        Set<Long> out = new HashSet<>();\n")
                    .append("        for (").append(o).append(" x : xs) { Long id = ").append(oLower).append("ToId(x); if (id!=null) out.add(id);} \n")
                    .append("        return out;\n")
                    .append("    }\n");
        }

        // self helpers para parent/children si aplica
        boolean hasSelfOneMany = false;
        for (Rel r : allRels) {
            if (e.equals(r.source) && e.equals(r.target)) {
                Card[] cc = cardsFor(r);
                if ((cc[0]==Card.ONE && cc[1]==Card.MANY) || (cc[0]==Card.MANY && cc[1]==Card.ONE)) {
                    hasSelfOneMany = true; break;
                }
            }
        }
        if (hasSelfOneMany) {
            m.append("    default ").append(e).append(" parentFromId(Long id) {\n")
                    .append("        if (id == null) return null;\n")
                    .append("        ").append(e).append(" x = new ").append(e).append("(); x.setId(id); return x;\n")
                    .append("    }\n")
                    .append("    default Set<").append(e).append("> childrenFromIds(Set<Long> ids) {\n")
                    .append("        if (ids == null) return null;\n")
                    .append("        Set<").append(e).append("> out = new HashSet<>();\n")
                    .append("        for (Long id : ids) { ").append(e).append(" x = new ").append(e).append("(); x.setId(id); out.add(x);} \n")
                    .append("        return out;\n")
                    .append("    }\n")
                    .append("    default Long parentToId(").append(e).append(" x) { return x==null?null:x.getId(); }\n")
                    .append("    default Set<Long> childrenToIds(Set<").append(e).append("> xs) {\n")
                    .append("        if (xs == null) return null;\n")
                    .append("        Set<Long> out = new HashSet<>();\n")
                    .append("        for (").append(e).append(" x : xs) { Long id = parentToId(x); if (id!=null) out.add(id);} \n")
                    .append("        return out;\n")
                    .append("    }\n");
        }

        // qualifiers para usar helpers en mappings
        m.append("\n    @ObjectFactory\n")
                .append("    default ").append(e).append(" mapIdToEntity(Long id) {\n")
                .append("        if (id == null) return null; ").append(e).append(" x = new ").append(e).append("(); x.setId(id); return x;\n")
                .append("    }\n");

        m.append("}\n");
        return m.toString();
    }



    private String renderPom(String packageBase, String artifactId) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>" + packageBase + "</groupId>\n" +
                "  <artifactId>" + artifactId + "</artifactId>\n" +
                "  <version>0.0.1-SNAPSHOT</version>\n" +
                "  <properties>\n" +
                "    <java.version>21</java.version>\n" +
                "    <spring-boot.version>3.3.3</spring-boot.version>\n" +
                "    <mapstruct.version>1.5.5.Final</mapstruct.version>\n" +
                "    <lombok.version>1.18.32</lombok.version>\n" +
                "  </properties>\n" +
                "  <dependencyManagement>\n" +
                "    <dependencies>\n" +
                "      <dependency>\n" +
                "        <groupId>org.springframework.boot</groupId>\n" +
                "        <artifactId>spring-boot-dependencies</artifactId>\n" +
                "        <version>${spring-boot.version}</version>\n" +
                "        <type>pom</type>\n" +
                "        <scope>import</scope>\n" +
                "      </dependency>\n" +
                "    </dependencies>\n" +
                "  </dependencyManagement>\n" +
                "  <dependencies>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.boot</groupId>\n" +
                "      <artifactId>spring-boot-starter-web</artifactId>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.boot</groupId>\n" +
                "      <artifactId>spring-boot-starter-data-jpa</artifactId>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>com.mysql</groupId>\n" +
                "      <artifactId>mysql-connector-j</artifactId>\n" +
                "      <scope>runtime</scope>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springframework.boot</groupId>\n" +
                "      <artifactId>spring-boot-starter-validation</artifactId>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.springdoc</groupId>\n" +
                "      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>\n" +
                "      <version>2.5.0</version>\n" +
                "    </dependency>\n" +
                "    <!-- MapStruct -->\n" +
                "    <dependency>\n" +
                "      <groupId>org.mapstruct</groupId>\n" +
                "      <artifactId>mapstruct</artifactId>\n" +
                "      <version>${mapstruct.version}</version>\n" +
                "    </dependency>\n" +
                "    <dependency>\n" +
                "      <groupId>org.mapstruct</groupId>\n" +
                "      <artifactId>mapstruct-processor</artifactId>\n" +
                "      <version>${mapstruct.version}</version>\n" +
                "      <scope>provided</scope>\n" +
                "    </dependency>\n" +
                "    <!-- Lombok (opcional, facilita getters/setters de relaciones) -->\n" +
                "    <dependency>\n" +
                "      <groupId>org.projectlombok</groupId>\n" +
                "      <artifactId>lombok</artifactId>\n" +
                "      <version>${lombok.version}</version>\n" +
                "      <scope>provided</scope>\n" +
                "    </dependency>\n" +
                "  </dependencies>\n" +
                "  <build>\n" +
                "    <plugins>\n" +
                "      <plugin>\n" +
                "        <groupId>org.springframework.boot</groupId>\n" +
                "        <artifactId>spring-boot-maven-plugin</artifactId>\n" +
                "      </plugin>\n" +
                "      <plugin>\n" +
                "        <groupId>org.apache.maven.plugins</groupId>\n" +
                "        <artifactId>maven-compiler-plugin</artifactId>\n" +
                "        <configuration>\n" +
                "          <source>${java.version}</source>\n" +
                "          <target>${java.version}</target>\n" +
                "          <annotationProcessorPaths>\n" +
                "            <path>\n" +
                "              <groupId>org.mapstruct</groupId>\n" +
                "              <artifactId>mapstruct-processor</artifactId>\n" +
                "              <version>${mapstruct.version}</version>\n" +
                "            </path>\n" +
                "            <path>\n" +
                "              <groupId>org.projectlombok</groupId>\n" +
                "              <artifactId>lombok</artifactId>\n" +
                "              <version>${lombok.version}</version>\n" +
                "            </path>\n" +
                "          </annotationProcessorPaths>\n" +
                "        </configuration>\n" +
                "      </plugin>\n" +
                "    </plugins>\n" +
                "  </build>\n" +
                "</project>\n";
    }

    private String renderApplication(String packageBase) {
        return "package " + packageBase + ";\n\n" +
                "import org.springframework.boot.SpringApplication;\n" +
                "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n" +
                "@SpringBootApplication\n" +
                "public class Application {\n" +
                "  public static void main(String[] args) {\n" +
                "    SpringApplication.run(Application.class, args);\n" +
                "  }\n" +
                "}\n";
    }

    private String renderProperties(String artifactId) {
        return "spring.datasource.url=jdbc:mysql://localhost:3306/" + artifactId + "?useSSL=false&serverTimezone=UTC\n" +
                "spring.datasource.username=root\n" +
                "spring.datasource.password=secret\n" +
                "spring.jpa.hibernate.ddl-auto=update\n" +
                "spring.jpa.show-sql=true\n";
    }

    // ===================== Helpers =====================

    private static RelKind toRelKind(String raw) {
        if (raw == null) return RelKind.UNKNOWN;
        String s = raw.toLowerCase(Locale.ROOT).replace("uml.", "").replace("custom.", "");
        return switch (s) {
            case "association" -> RelKind.ASSOCIATION;
            case "aggregation" -> RelKind.AGGREGATION;
            case "composition" -> RelKind.COMPOSITION;
            case "generalization" -> RelKind.GENERALIZATION;
            case "dependency" -> RelKind.DEPENDENCY;
            case "realization" -> RelKind.REALIZATION;
            default -> RelKind.UNKNOWN;
        };
    }

    private static Card toCard(String mult) {
        String m = (mult == null) ? "" : mult.trim();
        if (m.equals("*") || m.equals("0..*") || m.equals("1..*")) return Card.MANY;
        // tratamos "0..1" como ONE (opcional)
        return Card.ONE;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Card[] cardsFor(Rel r) {
        RelKind k = r.kind;
        String ms = r.multSource;
        String mt = r.multTarget;

        // Association: usa multiplicidades tal cual (fallback a ONE si no reconoce)
        if (k == RelKind.ASSOCIATION) {
            return new Card[]{ toCard(ms), toCard(mt) };
        }

        // Aggregation / Composition: default 1 -> * si no hay multiplicidades
        if (k == RelKind.AGGREGATION || k == RelKind.COMPOSITION) {
            if (isBlank(ms) && isBlank(mt)) {
                return new Card[]{ Card.ONE, Card.MANY }; // << default
            }
            return new Card[]{ toCard(ms), toCard(mt) };
        }

        // Otras (generalization/…): no generan campos; devolver ONE-ONE por defecto
        return new Card[]{ Card.ONE, Card.ONE };
    }

    private static String toTableName(String entityName) {
        return entityName == null ? "tabla" : entityName.toLowerCase(Locale.ROOT) + "s";
    }

    private static String cap(String s) {
        return (s == null || s.isEmpty()) ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String lower(String s) {
        return (s == null || s.isEmpty()) ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String plural(String s) {
        if (s == null || s.isEmpty()) return s;
        // naive plural
        if (s.endsWith("s")) return s + "es";
        return s + "s";
    }

    private static String safeVar(String base, Set<String> used) {
        String b = base;
        int i = 2;
        while (used.contains(b)) {
            b = base + i;
            i++;
        }
        return b;
    }

    private static String optString(Object o) {
        return o == null ? null : o.toString();
    }
}
