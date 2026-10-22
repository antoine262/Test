# xsd-validation

Plugin XJC qui transforme les contraintes d'un XSD en annotations Jakarta Validation
pendant la génération des classes JAXB.

    mvn clean verify          # construit le plugin, génère les classes de `example`, lance les tests

Utilisation dans un autre projet : voir example/pom.xml (jaxb-maven-plugin + <arg>-Xvalidation</arg>
+ dépendance du plugin). Dépendances du projet consommateur : jaxb-runtime, jakarta.validation-api,
hibernate-validator, expressly (et jackson-databind si JSON).
