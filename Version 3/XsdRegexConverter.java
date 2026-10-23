package com.example.xjcval;

/**
 * Convertit une expression régulière XML Schema en regex Java.
 * Gère : ancrage implicite (Hibernate utilise matches()), ^ et $ littéraux,
 * \i \I \c \C, \d \w \s, \p{IsXxx}, soustraction de classes [a-z-[aeiou]].
 */
public final class XsdRegexConverter {
    private static final String NAME_START = "_:A-Za-z";
    private static final String NAME_CHAR = "\\-._:A-Za-z0-9";

    private final String s;
    private int i;
    private final StringBuilder out = new StringBuilder();

    private XsdRegexConverter(String s) { this.s = s; }

    public static String toJava(String xsdRegex) {
        XsdRegexConverter c = new XsdRegexConverter(xsdRegex);
        c.run();
        return c.out.toString();
    }

    private void run() {
        while (i < s.length()) {
            char c = s.charAt(i);
            switch (c) {
                case '[' -> { i++; out.append(charClass()); }
                case '\\' -> out.append(escape());
                case '^' -> { out.append("\\^"); i++; }   // littéral en XSD
                case '$' -> { out.append("\\$"); i++; }   // littéral en XSD
                default -> { out.append(c); i++; }
            }
        }
    }

    private String charClass() {
        StringBuilder sb = new StringBuilder("[");
        if (i < s.length() && s.charAt(i) == '^') { sb.append('^'); i++; }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ']') { i++; break; }
            if (c == '\\') {
                sb.append(escape());
            } else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i += 2;                                   // soustraction de classe
                sb.append("&&[^").append(charClass()).append(']');
            } else if (c == '&') {
                sb.append("\\&"); i++;
            } else {
                sb.append(c); i++;
            }
        }
        return sb.append(']').toString();
    }

    private String escape() {
        char n = s.charAt(i + 1);
        i += 2;
        return switch (n) {
            case 'i' -> "[" + NAME_START + "]";
            case 'I' -> "[^" + NAME_START + "]";
            case 'c' -> "[" + NAME_CHAR + "]";
            case 'C' -> "[^" + NAME_CHAR + "]";
            case 'd' -> "\\p{Nd}";
            case 'D' -> "\\P{Nd}";
            case 'w' -> "[^\\p{P}\\p{Z}\\p{C}]";
            case 'W' -> "[\\p{P}\\p{Z}\\p{C}]";
            case 's' -> "[ \\t\\n\\r]";
            case 'S' -> "[^ \\t\\n\\r]";
            case 'p', 'P' -> {
                int end = s.indexOf('}', i);
                String name = s.substring(i + 1, end);
                i = end + 1;
                if (name.startsWith("Is")) name = "In" + name.substring(2); // blocs Unicode
                yield "\\" + n + "{" + name + "}";
            }
            default -> "\\" + n;
        };
    }
}
