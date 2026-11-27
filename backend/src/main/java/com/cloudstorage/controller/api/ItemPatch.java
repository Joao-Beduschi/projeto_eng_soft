package com.cloudstorage.controller.api;

import com.cloudstorage.exception.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

// A missing destination leaves the item in place. An explicit null moves it to the root.
record ItemPatch(boolean hasName, String name, boolean hasDestination, UUID destination) {
    static ItemPatch parse(Map<String, Object> fields, String destinationField) {
        if (fields == null || fields.isEmpty() || !Set.of("name", destinationField).containsAll(fields.keySet())) {
            throw invalid("error.patch.fields");
        }
        boolean hasName = fields.containsKey("name");
        Object name = fields.get("name");
        if (hasName && !(name instanceof String)) {
            throw invalid("error.patch.name");
        }
        return new ItemPatch(hasName, (String) name, fields.containsKey(destinationField),
                parseDestination(fields.get(destinationField), destinationField));
    }

    private static UUID parseDestination(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                // Invalid strings and non-string values use the same API error.
            }
        }
        throw invalid("error.patch.destination", field);
    }

    private static ApiException invalid(String key, Object... arguments) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_patch", key, arguments);
    }
}
