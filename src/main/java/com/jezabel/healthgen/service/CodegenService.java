package com.jezabel.healthgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.codegen.TypeMapper;
import com.jezabel.healthgen.domain.ModelSpecEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class CodegenService {

    private final ObjectMapper objectMapper;

    public CodegenService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Genera proyecto completo (Entities + Repo + Service + Controller + pom + main + props) */
    public Map<String, Object> generateEntities(ModelSpecEntity specEntity) throws IOException {
        Map<String, Object> spec = objectMapper.readValue(
                specEntity.getJson(), new TypeReference<Map<String, Object>>() {});

        String packageBase = (String) spec.getOrDefault("packageBase", "com.example.demo");
        String artifactId = (String) spec.getOrDefault("name", "generated-app");

        Path root = Files.createTempDirectory("healthgen-");
        Path srcMainJava = root.resolve("src/main/java/" + packageBase.replace('.', '/'));
        Path domainDir = srcMainJava.resolve("domain");
        Files.createDirectories(domainDir);

        List<Map<String, Object>> entities =
                (List<Map<String, Object>>) spec.getOrDefault("entities", List.of());

        List<String> createdFiles = new ArrayList<>();

        for (Map<String, Object> e : entities) {
            String entityName = (String) e.get("name");
            if (entityName == null || entityName.isBlank()) continue;

            List<Map<String, Object>> attrs =
                    (List<Map<String, Object>>) e.getOrDefault("attributes", List.of());

            // Entity
            String code = renderEntity(packageBase, entityName, attrs);
            Path target = domainDir.resolve(entityName + ".java");
            Files.createDirectories(target.getParent());
            Files.writeString(target, code, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(target).toString().replace("\\", "/"));

            // Repository
            Path repoFile = srcMainJava.resolve("repository/" + entityName + "Repository.java");
            Files.createDirectories(repoFile.getParent());
            Files.writeString(repoFile, renderRepository(packageBase, entityName),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(repoFile).toString().replace("\\","/"));

            // Service
            Path serviceFile = srcMainJava.resolve("service/" + entityName + "Service.java");
            Files.createDirectories(serviceFile.getParent());
            Files.writeString(serviceFile, renderService(packageBase, entityName),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(serviceFile).toString().replace("\\","/"));

            // Controller
            Path controllerFile = srcMainJava.resolve("controller/" + entityName + "Controller.java");
            Files.createDirectories(controllerFile.getParent());
            Files.writeString(controllerFile, renderController(packageBase, entityName),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            createdFiles.add(root.relativize(controllerFile).toString().replace("\\","/"));
        }

        // Archivos extra
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

    // ====== Plantillas ======

    private String renderEntity(String packageBase, String entityName, List<Map<String, Object>> attrs) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageBase).append(".domain;\n\n")
                .append("import jakarta.persistence.*;\n")
                .append("import java.io.Serializable;\n\n")
                .append("@Entity\n")
                .append("@Table(name = \"").append(toTableName(entityName)).append("\")\n")
                .append("public class ").append(entityName).append(" implements Serializable {\n\n");

        for (Map<String, Object> a : attrs) {
            String name = (String) a.get("name");
            String type = TypeMapper.toJavaType((String) a.get("type"));
            boolean pk = Boolean.TRUE.equals(a.get("pk"));
            String generated = a.get("generated") != null ? a.get("generated").toString() : null;

            if (name == null || name.isBlank()) continue;

            if (pk) {
                sb.append("    @Id\n");
                if ("IDENTITY".equalsIgnoreCase(generated)) {
                    sb.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
                }
            }
            sb.append("    @Column(name = \"").append(name).append("\")\n");
            sb.append("    private ").append(type).append(" ").append(name).append(";\n\n");
        }

        for (Map<String, Object> a : attrs) {
            String name = (String) a.get("name");
            String type = TypeMapper.toJavaType((String) a.get("type"));
            if (name == null || name.isBlank()) continue;

            sb.append("    public ").append(type).append(" get")
                    .append(cap(name)).append("() { return ").append(name).append("; }\n");
            sb.append("    public void set").append(cap(name))
                    .append("(").append(type).append(" ").append(name).append(") { this.")
                    .append(name).append(" = ").append(name).append("; }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toTableName(String entityName) {
        return entityName == null ? "tabla" : entityName.toLowerCase() + "s";
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
        String var = entityName.substring(0,1).toLowerCase() + entityName.substring(1);
        return "package " + packageBase + ".controller;\n\n" +
                "import " + packageBase + ".domain." + entityName + ";\n" +
                "import " + packageBase + ".service." + entityName + "Service;\n" +
                "import org.springframework.web.bind.annotation.*;\n" +
                "import java.util.*;\n\n" +
                "@RestController\n" +
                "@RequestMapping(\"/api/" + var + "s\")\n" +
                "public class " + entityName + "Controller {\n" +
                "    private final " + entityName + "Service service;\n\n" +
                "    public " + entityName + "Controller(" + entityName + "Service service) { this.service = service; }\n\n" +
                "    @PostMapping public " + entityName + " create(@RequestBody " + entityName + " e){ return service.save(e); }\n" +
                "    @GetMapping public List<" + entityName + "> all(){ return service.findAll(); }\n" +
                "    @GetMapping(\"/{id}\") public " + entityName + " one(@PathVariable Long id){ return service.findById(id).orElseThrow(); }\n" +
                "    @DeleteMapping(\"/{id}\") public void delete(@PathVariable Long id){ service.delete(id); }\n" +
                "}\n";
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

    public Path generateZip(ModelSpecEntity specEntity) throws IOException {
        Map<String, Object> result = generateEntities(specEntity);
        String tmpDir = (String) result.get("tmpDir");

        Path root = Path.of(tmpDir);
        System.out.println("📂 Generando ZIP desde: " + root);

        Path zipFile = Files.createTempFile("healthgen-", ".zip");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walk(root).forEach(path -> {
                try {
                    if (Files.isRegularFile(path)) {
                        Path relative = root.relativize(path);
                        String entryName = relative.toString().replace("\\", "/");

                        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        System.out.println("✅ ZIP creado en: " + zipFile.toAbsolutePath());
        return zipFile;
    }
}
