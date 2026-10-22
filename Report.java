package com.example.xsdval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Récapitulatif des contraintes ajoutées et de celles qui n'ont pas pu l'être. */
public final class Report {
    private final List<String> added = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void added(String s) { added.add(s); }
    public void warn(String s) { warnings.add(s); }
    public List<String> added() { return added; }
    public List<String> warnings() { return warnings; }

    public void write(Path p) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder("== Annotations ajoutées (" + added.size() + ") ==\n");
        added.forEach(a -> sb.append("  + ").append(a).append('\n'));
        sb.append("\n== Non traduit / à vérifier (").append(warnings.size()).append(") ==\n");
        warnings.forEach(w -> sb.append("  ! ").append(w).append('\n'));
        Files.writeString(p, sb.toString());
    }
}
