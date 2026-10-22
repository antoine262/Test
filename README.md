# xsd-validation-maven-plugin

Ajoute des annotations Jakarta Validation (jakarta.validation.constraints.*) aux classes Java
générées depuis un XSD. Goal : `xsdval:annotate` (phase process-sources).

```xml
<plugin>
  <groupId>com.example</groupId>
  <artifactId>xsd-validation-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions><execution><goals><goal>annotate</goal></goals></execution></executions>
  <configuration>
    <xsdFiles><xsdFile>${project.basedir}/src/main/resources/xsd/order.xsd</xsdFile></xsdFiles>
    <sourceDirectory>${project.build.directory}/generated-sources/xjc</sourceDirectory>
    <failOnUnmapped>false</failOnUnmapped>
  </configuration>
</plugin>
```
Placer ce plugin APRÈS le plugin XJC (org.jvnet.jaxb:jaxb-maven-plugin 4.x) dans le pom.
Dépendances du projet consommateur : jakarta.validation-api 3.0.2, hibernate-validator 8.0.x,
org.glassfish.expressly:expressly 5.0.0, jakarta.xml.bind-api 4.0.x.
