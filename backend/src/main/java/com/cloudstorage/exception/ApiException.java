package com.cloudstorage.exception;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String messageKey;
    private final Object[] arguments;

    public ApiException(HttpStatus status, String code) {
        this(status, code, "error." + code);
    }

    public ApiException(HttpStatus status, String code, String messageKey, Object... arguments) {
        super(code);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
        this.arguments = arguments.clone();
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String resolveMessage(MessageSource messages) {
        return messages.getMessage(messageKey, arguments, LocaleContextHolder.getLocale());
    }
}
