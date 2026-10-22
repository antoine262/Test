package com.example.xsdval;

import com.example.xsdval.java.SourceAnnotator;
import com.example.xsdval.xsd.XsdReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAnnotatorTest {

    private static final String ORDER = """
            package com.example.model;

            import jakarta.xml.bind.annotation.*;
            import java.util.List;

            @XmlAccessorType(XmlAccessType.FIELD)
            @XmlType(name = "")
            @XmlRootElement(name = "order")
            public class Order {
                @XmlElement(required = true)
                protected String customerEmail;
                @XmlElement(required = true)
                protected Address billTo;
                protected Address shipTo;
                @XmlElement(required = true)
                protected List<Item> item;
                protected String creditCard;
                protected String iban;
                protected List<String> tags;
                @XmlAttribute(name = "order-id", required = true)
                protected String orderId;
            }
            """;

    @Test
    void annotatesAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Path xsd = Path.of(getClass().getResource("/order.xsd").toURI());
        Report report = new Report();
        var model = new XsdReader(report).read(List.of(xsd.toFile()));

        Path src = tmp.resolve("Order.java");
        Files.writeString(src, ORDER);
        var annotator = new SourceAnnotator(model, report);

        assertThat(annotator.annotate(src)).isTrue();
        String once = Files.readString(src);
        assertThat(once).contains("@NotNull", "@Valid", "@NotEmpty", "@Size(max = 100)",
                "@Size(max = 50)", "@Size(max = 20) String", "@Pattern(regexp", "isChoice1Valid",
                "import jakarta.validation.constraints.NotNull;");
        assertThat(once).doesNotContain("javax.");
        assertThat(report.warnings()).anyMatch(w -> w.contains("uniqueSku"));

        assertThat(annotator.annotate(src)).isFalse();     // idempotence
        assertThat(Files.readString(src)).isEqualTo(once);
    }
}
