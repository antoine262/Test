package com.example.xsdval;

import com.example.xsdval.java.SourceAnnotator;
import com.example.xsdval.model.XsdModel.ClassInfo;
import com.example.xsdval.xsd.XsdReader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lit un ou plusieurs XSD et ajoute des annotations Jakarta Validation
 * dans les classes Java déjà générées (XJC ou autre).
 */
@Mojo(name = "annotate", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class AnnotateMojo extends AbstractMojo {

    /** XSD sources de vérité. */
    @Parameter(required = true)
    private List<File> xsdFiles;

    /** Répertoire des sources Java générées à annoter. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/xjc")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/xsd-validation-report.txt")
    private File reportFile;

    /** Échoue si une contrainte XSD n'a pas pu être traduite. */
    @Parameter(defaultValue = "false")
    private boolean failOnUnmapped;

    @Parameter(defaultValue = "false", property = "xsdval.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("xsd-validation: ignoré (skip=true)");
            return;
        }
        try {
            run();
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Échec de l'annotation des classes", e);
        }
    }

    /** Point d'entrée de la logique : XSD -> modèle -> annotations dans les .java. */
    private void run() throws Exception {
        Report report = new Report();

        Map<String, ClassInfo> model = new XsdReader(report).read(xsdFiles);
        getLog().info("XSD lus : " + model.size() + " types complexes");

        if (!sourceDirectory.isDirectory()) {
            getLog().warn("Répertoire de sources introuvable : " + sourceDirectory);
            return;
        }

        SourceAnnotator annotator = new SourceAnnotator(model, report);
        List<Path> files;
        try (Stream<Path> s = Files.walk(sourceDirectory.toPath())) {
            files = s.filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !p.endsWith("package-info.java") && !p.endsWith("ObjectFactory.java"))
                     .toList();
        }
        int modified = 0;
        for (Path p : files) {
            if (annotator.annotate(p)) modified++;
        }

        report.write(reportFile.toPath());
        getLog().info(modified + " fichier(s) modifié(s), " + report.added().size()
                + " annotation(s) ajoutée(s). Rapport : " + reportFile);
        report.warnings().forEach(w -> getLog().warn(w));

        if (failOnUnmapped && !report.warnings().isEmpty()) {
            throw new MojoFailureException(report.warnings().size()
                    + " contrainte(s) XSD non traduite(s), voir " + reportFile);
        }
    }
}
