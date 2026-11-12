package com.cloudstorage.model;

public record StoredObject(long sizeBytes, String sha256, String detectedMimeType) {
}
