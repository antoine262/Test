package com.example.xjcval;

import org.junit.jupiter.api.Test;

import java.util.List;
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
    @Test void emailLikePatternWithWhitespaceClass() {
        String re = XsdRegexConverter.toJava("[^@\\s]+@[^@\\s]+\\.[a-z]{2,}");
        assertThat(Pattern.matches(re, "client@example.fr")).isTrue();
        assertThat(Pattern.matches(re, "pas-un-email")).isFalse();
    }
    @Test void facetsRegexAndsStepsTogether() {
        Facets f = new Facets(null, null, null, null, null, null, null, null, null,
                List.of("[a-z]+", ".{3}"), List.of(), List.of(), null);
        assertThat(Pattern.matches(f.regex(), "abc")).isTrue();
        assertThat(Pattern.matches(f.regex(), "abcd")).isFalse();
    }
}
