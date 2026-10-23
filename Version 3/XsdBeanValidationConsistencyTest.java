package com.example.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import org.junit.jupiter.api.Test;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.example.model.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cohérence XSD <-> Bean Validation : un même document doit être jugé de la même façon par le schéma
 * (validation XML) et par les annotations générées (validation des objets).
 */
class XsdBeanValidationConsistencyTest {

    private record Parsed(Order order, List<String> schemaErrors) {}

    private static Parsed parse(String sample) throws Exception {
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(XsdBeanValidationConsistencyTest.class.getResource("/xsd/order.xsd"));
        Unmarshaller unmarshaller = JAXBContext.newInstance(Order.class).createUnmarshaller();
        unmarshaller.setSchema(schema);

        List<String> errors = new ArrayList<>();
        unmarshaller.setEventHandler(event -> { errors.add(event.getMessage()); return true; });   // on continue : on veut l'objet

        try (InputStream in = XsdBeanValidationConsistencyTest.class.getResourceAsStream("/samples/" + sample)) {
            return new Parsed((Order) unmarshaller.unmarshal(in), errors);
        }
    }

    @Test void validDocumentIsAcceptedByBothLayers() throws Exception {
        Parsed p = parse("valid-order.xml");
        assertThat(p.schemaErrors()).isEmpty();
        assertThat(VALIDATOR.validate(p.order())).isEmpty();
    }

    @Test void invalidDocumentIsRejectedByBothLayers() throws Exception {
        Parsed p = parse("invalid-order.xml");
        assertThat(p.schemaErrors()).isNotEmpty();                                           // le XSD refuse
        assertThat(paths(p.order())).contains("customerEmail", "item[0].quantity", "item[0].sku");   // et les annotations aussi
    }
}
