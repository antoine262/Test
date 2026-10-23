package com.example.rules.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Réglages des règles, lus dans /rules/rules.properties (classpath).
 * Un fichier externe peut les surcharger : -Dvalidation.rules.file=/chemin/rules.properties
 * Une clé manquante fait échouer le démarrage avec un message explicite.
 */
public final class RuleSettings {
    private static final String RESOURCE = "/rules/rules.properties";
    private static final String OVERRIDE_PROPERTY = "validation.rules.file";

    private final Properties props;

    private RuleSettings(Properties props) { this.props = props; }

    public static RuleSettings load() {
        Properties p = new Properties();
        try (InputStream in = RuleSettings.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null) {
                try (Reader r = Files.newBufferedReader(Path.of(override), StandardCharsets.UTF_8)) { p.load(r); }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture des réglages impossible", e);
        }
        return new RuleSettings(p);
    }

    /** Pour les tests unitaires d'une règle. */
    public static RuleSettings of(Map<String, String> values) {
        Properties p = new Properties();
        p.putAll(values);
        return new RuleSettings(p);
    }

    public String string(String key) {
        String v = props.getProperty(key);
        if (v == null) throw new IllegalStateException("Clé manquante dans rules.properties : " + key);
        return v.trim();
    }

    /** Liste séparée par des virgules (valeurs vides ignorées). */
    public List<String> list(String key) {
        return Arrays.stream(string(key).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Set<String> set(String key) { return new LinkedHashSet<>(list(key)); }

    public BigDecimal decimal(String key) { return new BigDecimal(string(key)); }
}
