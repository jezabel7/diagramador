package com.jezabel.healthgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.ai.GeminiClient;
import com.jezabel.healthgen.domain.ModelSpecEntity;
import com.jezabel.healthgen.repository.ModelSpecRepository;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AiDocService {

    private final ModelSpecRepository specRepo;
    private final CodegenService codegen;
    private final GeminiClient gemini;
    private final ObjectMapper om;

    public AiDocService(ModelSpecRepository specRepo, CodegenService codegen, GeminiClient gemini, ObjectMapper om) {
        this.specRepo = specRepo;
        this.codegen = codegen;
        this.gemini = gemini;
        this.om = om;
    }

    public byte[] generatePdfFromId(Long id) throws Exception {
        ModelSpecEntity specEntity = specRepo.findById(id).orElseThrow();
        Map<String, Object> spec = om.readValue(specEntity.getJson(), new TypeReference<>() {});
        return generatePdfFromSpec(spec);
    }

    public byte[] generatePdfFromSpec(Map<String,Object> spec) throws Exception {
        // 1) Ejecuta codegen solo para listar archivos (temporal)
        ModelSpecEntity fake = new ModelSpecEntity(null,
                String.valueOf(spec.getOrDefault("name","generated-app")),
                String.valueOf(spec.getOrDefault("version","0.0.1")),
                om.writeValueAsString(spec),
                null);
        Map<String,Object> gen = codegen.generateEntities(fake);
        String artifactId = String.valueOf(gen.get("artifactId"));
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) gen.getOrDefault("files", List.of());

        // 2) Construir prompt compacto (evita payload gigante)
        String prompt = buildPrompt(spec, artifactId, files);

        // 3) Llamar Gemini → Markdown
        String markdown = gemini.generateText(prompt);
        if (markdown == null || markdown.isBlank()) markdown = "# Documentación\nNo se recibió contenido.";

        // 4) Markdown → HTML
        String html = mdToHtml(markdown);

        // 5) HTML → PDF
        return htmlToPdf(html);
    }

    private String buildPrompt(Map<String,Object> spec, String artifactId, List<String> files) {
        String name = String.valueOf(spec.getOrDefault("name", "generated-app"));
        String pkg  = String.valueOf(spec.getOrDefault("packageBase", "com.example.demo"));

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> entities = (List<Map<String,Object>>) spec.getOrDefault("entities", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> relations = (List<Map<String,Object>>) spec.getOrDefault("relations", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente técnico. Genera una documentación clara en **Markdown** para un proyecto Spring Boot generado automáticamente.\n\n");
        sb.append("## Metadatos\n");
        sb.append("- Proyecto: ").append(name).append("\n");
        sb.append("- Package base: ").append(pkg).append("\n");
        sb.append("- ArtifactId: ").append(artifactId).append("\n\n");

        sb.append("## Estructura de archivos generados (resumen)\n");
        files.stream().sorted().limit(200).forEach(f -> sb.append("- ").append(f).append("\n"));
        if (files.size() > 200) sb.append("- ... (").append(files.size()-200).append(" más)\n");
        sb.append("\n");

        sb.append("## Modelo (entidades y atributos)\n");
        for (var e : entities) {
            sb.append("- **").append(e.get("name")).append("**\n");
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> attrs = (List<Map<String,Object>>) e.getOrDefault("attributes", List.of());
            for (var a : attrs) {
                sb.append("  - ").append(a.get("name")).append(": ").append(a.getOrDefault("type","STRING"));
                if (Boolean.TRUE.equals(a.get("pk"))) sb.append(" (PK)");
                sb.append("\n");
            }
        }
        sb.append("\n");

        if (!relations.isEmpty()) {
            sb.append("## Relaciones (según diagrama)\n");
            for (var r : relations) {
                sb.append("- ").append(r.get("type")).append(": ")
                        .append(r.get("source")).append(" [").append(Objects.toString(r.get("multSource"),"")).append("]")
                        .append(" → ").append(r.get("target")).append(" [").append(Objects.toString(r.get("multTarget"),"")).append("]\n");
            }
            sb.append("\n");
        }

        sb.append("## Requisitos previos\n")
                .append("- Java 21, Maven 3.9+\n")
                .append("- MySQL en localhost:3306 con usuario/clave configurados en `application.properties`\n\n");

        sb.append("## Cómo ejecutar localmente\n")
                .append("Incluye pasos: crear DB, configurar `application.properties`, `mvn spring-boot:run` y abrir Swagger en `/swagger-ui.html`.\n\n");

        sb.append("## Explicación del código generado\n")
                .append("Explica cada capa: `domain`, `repository`, `service`, `controller`, y cómo se reflejan las relaciones JPA.\n\n");

        sb.append("## Buenas prácticas y recomendaciones\n")
                .append("Incluye secciones de validación, DTOs, paginado, manejo de errores, pruebas, y seguridad.\n");

        sb.append("\n**Devuelve SOLO Markdown (sin HTML adicional).**\n");
        return sb.toString();
    }

    private String mdToHtml(String markdown) {
        MutableDataSet opts = new MutableDataSet();
        Parser parser = Parser.builder(opts).build();
        HtmlRenderer renderer = HtmlRenderer.builder(opts).build();
        Node doc = parser.parse(markdown);
        String body = renderer.render(doc);

        // Template HTML simple + CSS
        return """
               <!DOCTYPE html>
               <html lang="es">
               <head>
                 <meta charset="UTF-8" />
                 <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                 <style>
                   body { font-family: Arial, sans-serif; line-height: 1.45; color: #222; }
                   h1,h2,h3 { color: #0a3d62; }
                   code, pre { background: #f4f6f8; border: 1px solid #e5e7ea; padding: 2px 4px; }
                   pre { padding: 12px; overflow-x: auto; }
                   table { border-collapse: collapse; width: 100%; }
                   th, td { border: 1px solid #ddd; padding: 6px 8px; }
                   th { background: #f1f3f5; }
                   ul { margin: 0 0 8px 20px; }
                 </style>
               </head>
               <body>""" + body + "</body></html>";
    }

    private byte[] htmlToPdf(String html) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        b.withHtmlContent(html, null);
        b.toStream(out);
        b.run();
        return out.toByteArray();
    }
}
