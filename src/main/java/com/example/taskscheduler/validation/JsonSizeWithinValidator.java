package com.example.taskscheduler.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class JsonSizeWithinValidator implements ConstraintValidator<JsonSizeWithin, Map<String, Object>> {

    private final ObjectMapper objectMapper;
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
            size = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
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
