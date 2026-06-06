package com.example.taskscheduler.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JsonSizeWithinValidator Tests")
class JsonSizeWithinValidatorTest {

    private JsonSizeWithinValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new JsonSizeWithinValidator(new ObjectMapper());
        var annotation = mock(JsonSizeWithin.class);
        when(annotation.maxBytes()).thenReturn(64);
        validator.initialize(annotation);

        context = mock(ConstraintValidatorContext.class);
        var builder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        when(context.buildConstraintViolationWithTemplate(org.mockito.ArgumentMatchers.anyString())).thenReturn(builder);
    }

    @Test
    @DisplayName("Should accept null map")
    void shouldAcceptNullMap() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    @DisplayName("Should accept empty map")
    void shouldAcceptEmptyMap() {
        assertThat(validator.isValid(Map.of(), context)).isTrue();
    }

    @Test
    @DisplayName("Should accept map within size budget")
    void shouldAcceptSmallMap() {
        assertThat(validator.isValid(Map.of("k", "v"), context)).isTrue();
    }

    @Test
    @DisplayName("Should reject map exceeding size budget")
    void shouldRejectOversizedMap() {
        var oversized = new HashMap<String, Object>();
        oversized.put("blob", "x".repeat(128));
        assertThat(validator.isValid(oversized, context)).isFalse();
    }
}
