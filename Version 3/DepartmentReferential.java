package com.example.rules.user;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Référentiel département -> préfixes de code postal (referential/departments.properties). */
final class DepartmentReferential {
    private static final String RESOURCE = "/referential/departments.properties";

    private final Map<String, List<String>> prefixes = new HashMap<>();

    static DepartmentReferential load() {
        Properties p = new Properties();
        try (InputStream in = DepartmentReferential.class.getResourceAsStream(RESOURCE)) {
            p.load(new InputStreamReader(Objects.requireNonNull(in, RESOURCE + " introuvable"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DepartmentReferential r = new DepartmentReferential();
        p.forEach((dept, list) -> r.prefixes.put((String) dept,
                Arrays.stream(((String) list).split(",")).map(String::trim).toList()));
        return r;
    }

    /** Un département absent du référentiel est refusé. */
    boolean accepts(String department, String postalCode) {
        return prefixes.getOrDefault(department, List.of()).stream().anyMatch(postalCode::startsWith);
    }
}
