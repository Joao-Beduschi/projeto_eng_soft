package com.cloudstorage.repository;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public final class DatabaseConstraints {
    private DatabaseConstraints() {
    }

    public static boolean isUniqueViolation(DataIntegrityViolationException failure, String constraintName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "23505".equals(violation.getSQLState())
                        && constraintName.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
