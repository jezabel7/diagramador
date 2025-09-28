package com.jezabel.healthgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jezabel.healthgen.ai.GeminiClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiDiagramService {

    private final GeminiClient gemini;
    private final ObjectMapper om;

    public AiDiagramService(GeminiClient gemini, ObjectMapper om) {
        this.gemini = gemini;
        this.om = om;
    }

    /** Entrada: prompt libre del usuario. Salida: ModelSpec normalizado (Map). */
    public Map<String, Object> generateModelSpecFromPrompt(String userPrompt) {
        String system = """
Eres un generador de diagramas UML a JSON. 
Devuelve SOLO JSON **válido** (sin backticks, sin comentarios), con este esquema:

{
  "name": "string",                // nombre del proyecto
  "version": "string",             // ej. "0.0.1"
  "packageBase": "string",         // ej. "com.jezabel.healthgen"
  "entities": [
    {
      "name": "PascalCase",
      "attributes": [
        { "name":"camelCase", "type":"LONG|INT|BOOLEAN|DECIMAL|LOCAL_DATE|LOCAL_DATE_TIME|STRING", "pk":true|false, "generated":"IDENTITY"|null }
      ]
    }
  ],
  "relations": [
    { "type":"association|aggregation|composition|generalization",
      "source":"NombreEntidad",
      "target":"NombreEntidad",
      "multSource":"1|0..1|*|0..*|1..*",
      "multTarget":"1|0..1|*|0..*|1..*"
    }
  ]
}

Reglas:
- Asegúrate de que TODAS las entidades citadas en relations existan en entities.
- Si falta PK, agrega {"name":"id","type":"LONG","pk":true,"generated":"IDENTITY"}.
- Usa SOLO los tipos soportados arriba. 
- Nombres de clases en PascalCase, atributos en camelCase.
- Si dudas, prefiere asociaciones simples (association) con multiplicidades coherentes.

Devuelve exclusivamente el JSON.
""";

        String fullPrompt = system + "\n\nUsuario:\n" + userPrompt;
        String raw = gemini.generateText(fullPrompt);       // texto de Gemini
        String json = extractJson(raw);                     // remueve ``` o texto extra
        Map<String,Object> spec = readMap(json);            // parse

        Map<String,Object> normalized = normalizeSpec(spec, userPrompt);
        return normalized;
    }

    // ---------- Helpers ----------
    private Map<String,Object> readMap(String json) {
        try {
            return om.readValue(json, new TypeReference<>(){});
        } catch (Exception e) {
            throw new IllegalArgumentException("Respuesta IA no es JSON válido. Detalle: " + e.getMessage());
        }
    }

    private String extractJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        // quita fences ```json ... ```
        if (t.startsWith("```")) {
            t = t.replaceFirst("(?s)^```(json)?", "");
            t = t.replaceFirst("(?s)```$", "");
        }
        t = t.trim();
        if (t.startsWith("{") && t.endsWith("}")) return t;

        // fallback: toma primer bloque {...} balanceado simple
        int first = t.indexOf('{');
        int last = t.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return t.substring(first, last + 1);
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> normalizeSpec(Map<String,Object> spec, String userPrompt) {
        Map<String,Object> out = new LinkedHashMap<>();

        String name = optStr(spec.get("name"));
        if (name.isBlank()) name = guessNameFromPrompt(userPrompt);
        String version = optStr(spec.get("version")); if (version.isBlank()) version = "0.0.1";
        String pkg = optStr(spec.get("packageBase")); if (pkg.isBlank()) pkg = "com.jezabel.healthgen";

        List<Map<String,Object>> entities = (List<Map<String,Object>>) spec.getOrDefault("entities", List.of());
        List<Map<String,Object>> relations = (List<Map<String,Object>>) spec.getOrDefault("relations", List.of());

        // Normalizar entidades
        Map<String, Map<String,Object>> entitiesByName = new LinkedHashMap<>();
        for (Map<String,Object> e : entities) {
            String rawName = optStr(e.get("name"));
            String className = toPascal(rawName.isBlank() ? "Entity" : rawName);
            if (className.isBlank()) continue;

            // attrs
            List<Map<String,Object>> attrs = (List<Map<String,Object>>) e.getOrDefault("attributes", new ArrayList<>());
            List<Map<String,Object>> normAttrs = new ArrayList<>();
            boolean hasPk = false;
            for (Map<String,Object> a : attrs) {
                String an = toCamel(optStr(a.get("name")));
                if (an.isBlank()) continue;
                String type = normalizeType(optStr(a.get("type")));
                boolean pk = Boolean.TRUE.equals(a.get("pk"));
                String gen = optStr(a.get("generated"));
                if (pk) hasPk = true;

                Map<String,Object> na = new LinkedHashMap<>();
                na.put("name", an);
                na.put("type", type);
                if (pk) na.put("pk", true);
                if (!gen.isBlank()) na.put("generated", gen.toUpperCase(Locale.ROOT));
                normAttrs.add(na);
            }
            if (!hasPk) {
                Map<String,Object> id = new LinkedHashMap<>();
                id.put("name","id"); id.put("type","LONG"); id.put("pk",true); id.put("generated","IDENTITY");
                normAttrs.add(0, id);
            }

            Map<String,Object> ne = new LinkedHashMap<>();
            ne.put("name", className);
            ne.put("attributes", normAttrs);
            entitiesByName.put(className.toLowerCase(Locale.ROOT), ne);
        }

        // Normalizar relaciones
        List<Map<String,Object>> normRels = new ArrayList<>();
        for (Map<String,Object> r : relations) {
            String type = normalizeRelType(optStr(r.get("type")));
            if (type == null) continue;

            String s = optStr(r.get("source"));
            String t = optStr(r.get("target"));
            if (s.isBlank() || t.isBlank()) continue;

            String sKey = toPascal(s).toLowerCase(Locale.ROOT);
            String tKey = toPascal(t).toLowerCase(Locale.ROOT);
            if (!entitiesByName.containsKey(sKey) || !entitiesByName.containsKey(tKey)) continue;

            String m0 = normalizeMult(optStr(r.get("multSource")));
            String m1 = normalizeMult(optStr(r.get("multTarget")));

            Map<String,Object> nr = new LinkedHashMap<>();
            nr.put("type", type);
            nr.put("source", ((Map<String,Object>)entitiesByName.get(sKey)).get("name"));
            nr.put("target", ((Map<String,Object>)entitiesByName.get(tKey)).get("name"));
            if (type.equals("association") || type.equals("aggregation") || type.equals("composition")) {
                nr.put("multSource", m0);
                nr.put("multTarget", m1);
            }
            normRels.add(nr);
        }

        out.put("name", name);
        out.put("version", version);
        out.put("packageBase", pkg);
        out.put("entities", new ArrayList<>(entitiesByName.values()));
        if (!normRels.isEmpty()) out.put("relations", normRels);
        return out;
    }

    private String optStr(Object o){ return o==null? "": String.valueOf(o).trim(); }

    // Tipos soportados por tu TypeMapper
    private static final Set<String> VALID_TYPES = Set.of(
            "LONG","INT","BOOLEAN","DECIMAL","LOCAL_DATE","LOCAL_DATE_TIME","STRING"
    );
    private String normalizeType(String raw){
        String t = raw.toUpperCase(Locale.ROOT);
        return VALID_TYPES.contains(t) ? t : "STRING";
    }

    private String normalizeRelType(String raw){
        String v = raw.toLowerCase(Locale.ROOT).replace("uml.","").replace("custom.","");
        return switch (v) {
            case "association","aggregation","composition","generalization" -> v;
            default -> null;
        };
    }

    private String normalizeMult(String m){
        String s = m.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
        if (s.equals("*")) return "*";
        if (s.equals("0..*") || s.equals("1..*") || s.equals("0..1") || s.equals("1")) return s.toUpperCase(Locale.ROOT);
        // defaults
        if (s.isBlank()) return "1";
        if (s.equals("many")) return "0..*";
        if (s.equals("one")) return "1";
        return s.toUpperCase(Locale.ROOT);
    }

    private String toPascal(String raw){
        if (raw == null) return "";
        Matcher m = Pattern.compile("[A-Za-z0-9]+").matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()){
            String part = m.group();
            sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private String toCamel(String raw){
        String pas = toPascal(raw);
        if (pas.isEmpty()) return pas;
        return Character.toLowerCase(pas.charAt(0)) + pas.substring(1);
    }

    private String guessNameFromPrompt(String p){
        if (p == null || p.isBlank()) return "generated-app";
        // primer sustantivo simple
        Matcher m = Pattern.compile("[A-Za-z]{3,}").matcher(p);
        if (m.find()){
            return toPascal(m.group()) + "-app";
        }
        return "generated-app";
    }
}
