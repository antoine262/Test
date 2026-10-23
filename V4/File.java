        // Cardinalité du XSD (maxOccurs > 1). Ne pas utiliser prop.isCollection() : XJC le renvoie aussi
        // pour un xs:list (@XmlList), qui est une List Java mais un élément unique côté XSD.
        boolean repeated = max < 0 || max > 1;
        boolean required = repeated ? min >= 1
                : prop instanceof CElementPropertyInfo e ? e.isRequired()
                : prop instanceof CAttributePropertyInfo a ? a.isRequired()
                : prop instanceof CValuePropertyInfo;


        for (XSSimpleType t = st; t != null; t = t.getSimpleBaseType()) {
            boolean builtin = XS.equals(t.getTargetNamespace());

            if (t.isRestriction()) {
                boolean numericOk = builtin && NUMERIC_BUILTINS.contains(t.getName());
                List<String> stepPatterns = new ArrayList<>(), stepEnums = new ArrayList<>();
                for (Iterator<? extends XSFacet> it = t.asRestriction().iterateDeclaredFacets(); it.hasNext(); ) {
                    XSFacet f = it.next();
                    String n = f.getName(), v = f.getValue().value;
                    if (XSFacet.FACET_PATTERN.equals(n)) {
                        if (!builtin) stepPatterns.add(XsdRegexConverter.toJava(v));
                    } else if (XSFacet.FACET_ENUMERATION.equals(n)) {
                        if (!builtin) stepEnums.add(v);
                    } else if (!builtin || numericOk) {
                        single.putIfAbsent(n, v);              // length/min/maxLength d'une liste inclus
                    }
                }
                if (!stepPatterns.isEmpty()) {
                    steps.add(String.join("|", stepPatterns.stream().map(p -> "(?:" + p + ")").toList()));
                }
                if (!enumDone && !stepEnums.isEmpty()) { enums.addAll(stepEnums); enumDone = true; }

            } else if (t.isList()) {                            // xs:list : facettes des items résolues à part
                itemFacets = resolve(t.asList().getItemType());
                if (!steps.isEmpty() || !enums.isEmpty()) {
                    issues.add("pattern/enumeration sur un xs:list non couverts");
                    steps.clear();
                    enums.clear();
                }
                break;

            } else if (!builtin && t.isUnion()) {
                issues.add("xs:union non couvert");
                break;
            }
        }


import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderDefinedContext;





    /** Transmet les violations à Bean Validation, chacune rattachée à son champ. */
    public void writeTo(ConstraintValidatorContext ctx) {
        ctx.disableDefaultConstraintViolation();
        for (Entry e : entries) report(ctx, e);
    }

    private static void report(ConstraintValidatorContext ctx, Entry e) {
        ConstraintViolationBuilder builder = ctx.buildConstraintViolationWithTemplate(e.message());
        NodeBuilderCustomizableContext custom = null;   // dernier noeud, tant qu'aucun indice ne lui a été posé
        NodeBuilderDefinedContext defined = null;       // dernier noeud, une fois l'indice posé (atIndex)
        Integer pendingIndex = null;

        for (Object part : e.path()) {
            if (part instanceof Integer index) { pendingIndex = index; continue; }   // s'applique au noeud suivant
            String name = (String) part;

            NodeBuilderCustomizableContext next =
                    custom != null ? custom.addPropertyNode(name)
                  : defined != null ? defined.addPropertyNode(name)
                  : builder.addPropertyNode(name);

            if (pendingIndex != null) {
                defined = next.inIterable().atIndex(pendingIndex);
                custom = null;
                pendingIndex = null;
            } else {
                custom = next;
                defined = null;
            }
        }

        if (custom != null) custom.addConstraintViolation();
        else if (defined != null) defined.addConstraintViolation();
        else builder.addConstraintViolation();
    }








