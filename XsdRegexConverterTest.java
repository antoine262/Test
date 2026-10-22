package com.example.xjcval;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class XsdRegexConverterTest {
    @Test void digitsAndEscapes() {
        assertThat(Pattern.matches(XsdRegexConverter.toJava("[A-Z]{3}-\\d{4}"), "ABC-1234")).isTrue();
        assertThat(Pattern.matches(XsdRegexConverter.toJava("[A-Z]{3}-\\d{4}"), "ABC-12a4")).isFalse();
    }
    @Test void classSubtraction() {
        String re = XsdRegexConverter.toJava("[a-z-[aeiou]]+");
        assertThat(Pattern.matches(re, "bcd")).isTrue();
        assertThat(Pattern.matches(re, "bad")).isFalse();
    }
    @Test void caretAndDollarAreLiteral() {
        assertThat(Pattern.matches(XsdRegexConverter.toJava("a^b$"), "a^b$")).isTrue();
    }
    @Test void nameChars() {
        assertThat(Pattern.matches(XsdRegexConverter.toJava("\\i\\c*"), "my-name1")).isTrue();
        assertThat(Pattern.matches(XsdRegexConverter.toJava("\\i\\c*"), "1abc")).isFalse();
    }
    @Test void facetsRegexAndsStepsTogether() {
        Facets f = new Facets(null, null, null, null, null, null, null, null, null,
                java.util.List.of("[a-z]+", ".{3}"), java.util.List.of(), java.util.List.of());
        assertThat(Pattern.matches(f.regex(), "abc")).isTrue();
        assertThat(Pattern.matches(f.regex(), "abcd")).isFalse();
    }
}
