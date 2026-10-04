package com.example.taskscheduler.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class JsonSizeWithinValidator implements ConstraintValidator<JsonSizeWithin, Map<String, Object>> {

    private final JsonMapper jsonMapper;
    private int maxBytes;

    @Override
    public void initialize(JsonSizeWithin annotation) {
        this.maxBytes = annotation.maxBytes();
    }

    @Override
    public boolean isValid(Map<String, Object> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }

        int size;
        try {
            size = jsonMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (JacksonException e) {
            log.warn("Failed to serialize payload for size validation; rejecting: {}", e.getMessage());
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("payload is not valid JSON").addConstraintViolation();
            return false;
        }

        if (size > maxBytes) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    String.format("serialized JSON is %d bytes; maximum allowed is %d", size, maxBytes)
            ).addConstraintViolation();
            return false;
        }
        return true;
    }
}
