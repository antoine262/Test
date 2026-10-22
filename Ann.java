package com.example.xsdval.rules;

/** Description d'une annotation à poser. */
public record Ann(String name, String fqn, String source) {
    static final String C = "jakarta.validation.constraints.";

    static Ann of(String name, String... params) {
        String args = params.length == 0 ? "" : "(" + String.join(", ", params) + ")";
        return new Ann(name, C + name, "@" + name + args);
    }

    static Ann valid() { return new Ann("Valid", "jakarta.validation.Valid", "@Valid"); }

    public static String litPublic(String s) { return lit(s); }

    static String lit(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
