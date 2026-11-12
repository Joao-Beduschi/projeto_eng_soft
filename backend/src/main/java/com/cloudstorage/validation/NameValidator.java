package com.cloudstorage.validation;

import com.cloudstorage.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import org.springframework.http.HttpStatus;

public final class NameValidator {
    private NameValidator() {
    }

    public static String validate(String input, String kind) {
        if (input == null) {
            throw invalid(kind);
        }

        String name = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        if (name.isEmpty()
                || name.equals(".")
                || name.equals("..")
                || name.getBytes(StandardCharsets.UTF_8).length > 255
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(kind);
        }
        return name;
    }

    private static ApiException invalid(String kind) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "invalid_" + kind + "_name",
                "The " + kind + " name is invalid.");
    }
}
