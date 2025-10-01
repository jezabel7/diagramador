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
 * Con relaciones JPA (Association/Aggregation/Composition).
 */
@Service
public class CodegenService {

    private final ObjectMapper objectMapper;

    public CodegenService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Crea un zip del proyecto generado. */
    public Path generateZip(ModelSpecEntity specEntity) throws IOException {
        Map<String, Object> result = generateEntities(specEntity);
        Path root = Path.of((String) result.get("tmpDir"));
        Path zipFile = Files.createTempFile("healthgen-", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            String entryName = root.relativize(p).toString().replace("\\", "/");
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(p, zos);
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

    /** Genera estructura de proyecto en carpeta temporal. */
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
            if (rel.source != null && rel.target != null) {
                rels.add(rel);
            }
        }

        // FS salida
        Path root = Files.createTempDirectory("healthgen-");
        Path srcMainJava = root.resolve("src/main/java/" + packageBase.replace('.', '/'));
        Path domainDir = srcMainJava.resolve("domain");
        Files.createDirectories(domainDir);

        List<String> created = new ArrayList<>();

        // Entities + repo + service + controller
        for (EntitySpec es : entities.values()) {
            Path entityFile = domainDir.resolve(es.name + ".java");
            Files.createDirectories(entityFile.getParent());
            Files.writeString(entityFile, renderEntity(packageBase, es, rels, entities),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            created.add(rel(root, entityFile));

            Path repoFile = srcMainJava.resolve("repository/" + es.name + "Repository.java");
            Files.createDirectories(repoFile.getParent());
            Files.writeString(repoFile, renderRepository(packageBase, es.name),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            created.add(rel(root, repoFile));

            Path serviceFile = srcMainJava.resolve("service/" + es.name + "Service.java");
            Files.createDirectories(serviceFile.getParent());
            Files.writeString(serviceFile, renderService(packageBase, es.name),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            created.add(rel(root, serviceFile));

            Path controllerFile = srcMainJava.resolve("controller/" + es.name + "Controller.java");
            Files.createDirectories(controllerFile.getParent());
            Files.writeString(controllerFile, renderController(packageBase, es.name),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            created.add(rel(root, controllerFile));
        }

        // POM, Application, properties
        Path pomFile = root.resolve("pom.xml");
        Files.writeString(pomFile, renderPom(packageBase, artifactId),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        created.add(rel(root, pomFile));

        Path appFile = srcMainJava.resolve("Application.java");
        Files.createDirectories(appFile.getParent());
        Files.writeString(appFile, renderApplication(packageBase),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        created.add(rel(root, appFile));

        Path resDir = root.resolve("src/main/resources");
        Files.createDirectories(resDir);
        Path propsFile = resDir.resolve("application.properties");
        Files.writeString(propsFile, renderProperties(artifactId),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        created.add(rel(root, propsFile));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tmpDir", root.toString());
        out.put("packageBase", packageBase);
        out.put("artifactId", artifactId);
        out.put("files", created);
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace("\\", "/");
    }

    // ===================== ENTITY con relaciones =====================
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

        // campos simples
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

        // relaciones
        List<Rel> relsHere = new ArrayList<>();
        for (Rel r : allRels) {
            if (es.name.equals(r.source) || es.name.equals(r.target)) relsHere.add(r);
        }

        Set<String> used = new HashSet<>();
        for (Attr a : es.attrs) used.add(a.name);

        for (Rel r : relsHere) {
            RelKind kind = r.kind;
            if (!(kind == RelKind.ASSOCIATION || kind == RelKind.AGGREGATION || kind == RelKind.COMPOSITION)) continue;

            boolean self = r.source.equals(r.target);
            Card[] cc = cardsFor(r);
            Card cSrc = cc[0], cTgt = cc[1];

            boolean iAmSource = es.name.equals(r.source);
            String other = iAmSource ? r.target : r.source;

            String me = es.name, otherLower = lower(other);

            boolean dashedIsComposition = (kind == RelKind.COMPOSITION);
            boolean dashedIsAggregation = (kind == RelKind.AGGREGATION);

            String ownerByLex = (me.compareTo(other) <= 0) ? me : other;

            // self 1-*
            if (self && ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE))) {
                sb.append("    @ManyToOne");
                if (dashedIsComposition) sb.append("(cascade = CascadeType.ALL)");
                sb.append("\n    @JoinColumn(name = \"parent_id\")\n");
                sb.append("    private ").append(me).append(" parent;\n\n");

                sb.append("    @OneToMany(mappedBy = \"parent\"");
                if (dashedIsComposition) sb.append(", cascade = CascadeType.ALL, orphanRemoval = true");
                sb.append(")\n");
                sb.append("    private Set<").append(me).append("> children = new HashSet<>();\n\n");
                continue;
            }

            if (cSrc == Card.ONE && cTgt == Card.ONE) {
                boolean iAmOwner = me.equals(ownerByLex);
                String fieldName = safeVar(otherLower, used);
                if (iAmOwner) {
                    sb.append("    @OneToOne\n")
                            .append("    @JoinColumn(name = \"").append(otherLower).append("_id\")\n")
                            .append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                } else {
                    String mappedBy = lower(other);
                    sb.append("    @OneToOne(mappedBy = \"").append(mappedBy).append("\")\n")
                            .append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                }
                used.add(fieldName);
            } else if ((cSrc == Card.ONE && cTgt == Card.MANY) || (cSrc == Card.MANY && cTgt == Card.ONE)) {
                boolean iAmManySide = (iAmSource && cSrc == Card.MANY) || (!iAmSource && cTgt == Card.MANY);
                if (iAmManySide) {
                    String fieldName = safeVar(otherLower, used);
                    sb.append("    @ManyToOne\n")
                            .append("    @JoinColumn(name = \"").append(otherLower).append("_id\")\n")
                            .append("    private ").append(other).append(" ").append(fieldName).append(";\n\n");
                    used.add(fieldName);
                } else {
                    String fieldName = safeVar(plural(otherLower), used);
                    sb.append("    @OneToMany(mappedBy = \"").append(lower(me)).append("\"");
                    if (dashedIsComposition) {
                        sb.append(", cascade = CascadeType.ALL, orphanRemoval = true");
                    } else if (dashedIsAggregation) {
                        // sin cascada especial
                    }
                    sb.append(")\n")
                            .append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                    used.add(fieldName);
                }
            } else {
                boolean iAmOwner = me.equals(ownerByLex);
                String fieldName = safeVar(plural(otherLower), used);
                if (iAmOwner) {
                    String jt = (lower(me) + "_" + otherLower);
                    sb.append("    @ManyToMany\n")
                            .append("    @JoinTable(name = \"").append(jt).append("\",\n")
                            .append("        joinColumns = @JoinColumn(name = \"").append(lower(me)).append("_id\"),\n")
                            .append("        inverseJoinColumns = @JoinColumn(name = \"").append(otherLower).append("_id\"))\n")
                            .append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                } else {
                    String mappedBy = plural(lower(ownerByLex.equals(me) ? other : ownerByLex));
                    sb.append("    @ManyToMany(mappedBy = \"").append(mappedBy).append("\")\n")
                            .append("    private Set<").append(other).append("> ").append(fieldName).append(" = new HashSet<>();\n\n");
                }
                used.add(fieldName);
            }
        }

        // getters/setters simples (para campos básicos)
        for (Attr a : es.attrs) {
            String name = a.name;
            if (name == null || name.isBlank()) continue;
            String type = TypeMapper.toJavaType(a.type);
            String Cap = cap(name);
            sb.append("    public ").append(type).append(" get").append(Cap).append("() { return ").append(name).append("; }\n");
            sb.append("    public void set").append(Cap).append("(").append(type).append(" ").append(name).append(") { this.")
                    .append(name).append(" = ").append(name).append("; }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

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

    private String renderController(String packageBase, String entityName) {
        String var = lower(entityName);
        return ""
                + "package " + packageBase + ".controller;\n\n"
                + "import " + packageBase + ".domain." + entityName + ";\n"
                + "import " + packageBase + ".service." + entityName + "Service;\n"
                + "import org.springframework.http.ResponseEntity;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "import java.net.URI;\n"
                + "import java.util.*;\n\n"
                + "@RestController\n"
                + "@RequestMapping(\"/api/" + var + "s\")\n"
                + "public class " + entityName + "Controller {\n"
                + "    private final " + entityName + "Service service;\n\n"
                + "    public " + entityName + "Controller(" + entityName + "Service service) { this.service = service; }\n\n"
                + "    @PostMapping\n"
                + "    public ResponseEntity<" + entityName + "> create(@RequestBody " + entityName + " body){\n"
                + "        " + entityName + " saved = service.save(body);\n"
                + "        return ResponseEntity.created(URI.create(\"/api/" + var + "s/\" + saved.getId())).body(saved);\n"
                + "    }\n\n"
                + "    @GetMapping\n"
                + "    public List<" + entityName + "> all(){\n"
                + "        return service.findAll();\n"
                + "    }\n\n"
                + "    @GetMapping(\"/{id}\")\n"
                + "    public " + entityName + " one(@PathVariable Long id){\n"
                + "        return service.findById(id).orElseThrow();\n"
                + "    }\n\n"
                + "    @PutMapping(\"/{id}\")\n"
                + "    public " + entityName + " update(@PathVariable Long id, @RequestBody " + entityName + " body){\n"
                + "        " + entityName + " existing = service.findById(id).orElseThrow();\n"
                + "        body.setId(existing.getId());\n"
                + "        return service.save(body);\n"
                + "    }\n\n"
                + "    @DeleteMapping(\"/{id}\")\n"
                + "    public ResponseEntity<Void> delete(@PathVariable Long id){\n"
                + "        service.delete(id);\n"
                + "        return ResponseEntity.noContent().build();\n"
                + "    }\n"
                + "}\n";
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
                "  </dependencies>\n" +
                "  <build>\n" +
                "    <plugins>\n" +
                "      <plugin>\n" +
                "        <groupId>org.springframework.boot</groupId>\n" +
                "        <artifactId>spring-boot-maven-plugin</artifactId>\n" +
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

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Card toCard(String mult) {
        String m = (mult == null) ? "" : mult.trim();
        if (m.equals("*") || m.equals("0..*") || m.equals("1..*")) return Card.MANY;
        return Card.ONE; // también 0..1
    }

    private static Card[] cardsFor(Rel r) {
        RelKind k = r.kind;
        String ms = r.multSource, mt = r.multTarget;
        if (k == RelKind.ASSOCIATION) {
            return new Card[]{ toCard(ms), toCard(mt) };
        }
        if (k == RelKind.AGGREGATION || k == RelKind.COMPOSITION) {
            if (isBlank(ms) && isBlank(mt)) return new Card[]{ Card.ONE, Card.MANY };
            return new Card[]{ toCard(ms), toCard(mt) };
        }
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
        if (s.endsWith("s")) return s + "es";
        return s + "s";
    }
    private static String safeVar(String base, Set<String> used) {
        String b = base; int i = 2;
        while (used.contains(b)) { b = base + i; i++; }
        return b;
    }
    private static String optString(Object o) { return o == null ? null : o.toString(); }
}
