# xsd-validation

Trois sources de règles, une seule validation Bean Validation (Hibernate Validator) :

| Source | Où | Exemples |
|---|---|---|
| **XSD** | `example/src/main/resources/xsd/order.xsd` | occurrences, bornes, longueurs, patterns, digits, xs:choice, xs:list |
| **Fichier de règles** | `example/src/main/resources/validation-rules.xml` | branche une annotation sur une classe/champ généré |
| **Règles Java custom** | `example/src/main/java/com/example/rules/` | comparaisons objet/objet, valeurs, référentiel |
| **Réglages** | `example/src/main/resources/rules/rules.properties` | seuils, pays autorisés, catégories incompatibles |
| **Référentiel** | `example/src/main/resources/referential/*.properties` | département -> préfixes de code postal |
| **Messages** | `example/src/main/resources/ValidationMessages.properties` | textes d'erreur des règles custom |

    mvn clean verify

## Ajouter une règle custom (3 étapes)

1. Écrire une classe `Rule<Order>` (ou `Rule<User>`) : `check(root, violations)`.
2. L'ajouter à la liste de `OrderRulesValidator.createRules(...)`.
3. Ajouter son message dans `ValidationMessages.properties` (et un réglage dans `rules.properties` si besoin).

Pour une nouvelle classe racine : une annotation + un validator (copier `OrderRules`/`OrderRulesValidator`)
et une ligne `<class name="...">` dans `validation-rules.xml`.
