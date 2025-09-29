package com.jezabel.healthgen.controller;

import com.jezabel.healthgen.ai.GeminiClient;
import com.jezabel.healthgen.ai.IpRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/chat")
public class AiChatController {

    private final GeminiClient gemini;
    private final IpRateLimiter limiter;

    public AiChatController(GeminiClient gemini, IpRateLimiter limiter) {
        this.gemini = gemini;
        this.limiter = limiter;
    }

    @PostMapping
    public Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = clientIp(req);
        if (!limiter.allow(ip)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes, intenta en unos minutos.");
        }

        String question = String.valueOf(body.getOrDefault("question", "")).trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta 'question'.");
        }

        String context = """
                Eres el asistente de la app "Diagramador" (Front: Vite/React + JointJS, Back: Spring Boot).
                Responde SIEMPRE en español, en 3–5 líneas máximo, con pasos claros. Si procede, usa viñetas.
                Funciones clave:
                - "Generar Código": crea un proyecto Spring Boot con Entity/Repository/Service/Controller y JPA.
                  Relaciones: 1–1, 1–N, N–N; composición/agr. mapeadas con cascade/orphanRemoval cuando aplique.
                - "Generar PDF": guía del proyecto (estructura, cómo correrlo, notas).
                - "Diagrama con IA": crea clases/relaciones desde texto; puedes dictar por voz.
                - Colaboración en tiempo real: compartir URL con ?doc=...; multiplicidades sólo en Association.
                - Para añadir clases debe darle al botón de "+Clase", para editar sus atributos debe darle click a la misma y
                en la barra lateral le saldrán las opciones.
                - "Nuevo": Limpia el lienzo.
                - "Importar": Crea el diagrama de clases a base de un JSOn que le subas.
                - "Exportar": Crea un JSON del diagrama.
                - Tiene las relaciones solo de Asociacion, Agregación, Composición y Generalización.
                Para que puedas unir las clases con sus respectivas relaciones debes darle click a la de origen y la de destino.
                - Para las multiplicidades de Asociacion debes darle click a la línea de relación para poder modificar en el inspector de la barra lateral.
                Límites: no gestiona tu DB por ti; configura application.properties. No inventes si no sabes: sugiere abrir Swagger y revisar /api.
                Da respuestas breves y prácticas; evita texto irrelevante.
                """;

        String prompt = context + "\nUsuario: " + question + "\nAsistente (máx. 120 palabras, claro y concreto):";

        String answer = gemini.generateText(prompt);
        // recorte de seguridad por si el modelo se pasa
        if (answer.length() > 1200) answer = answer.substring(0, 1200) + "…";

        return Map.of("answer", answer);
    }

    private static String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
