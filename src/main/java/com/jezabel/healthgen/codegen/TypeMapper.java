package com.jezabel.healthgen.codegen;

public class TypeMapper {
    public static String toJavaType(String type) {
        if (type == null) return "String";
        return switch (type) {
            case "LONG" -> "Long";
            case "INT" -> "Integer";
            case "BOOLEAN" -> "Boolean";
            case "DECIMAL" -> "java.math.BigDecimal";
            case "LOCAL_DATE" -> "java.time.LocalDate";
            case "LOCAL_DATE_TIME" -> "java.time.LocalDateTime";
            default -> "String"; // STRING u otros por defecto
        };
    }
}
