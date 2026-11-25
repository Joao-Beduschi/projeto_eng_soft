package com.cloudstorage.dto;

import java.util.List;
import java.util.UUID;

public record FileVersionHistoryResponse(
        UUID fileId,
        int currentVersionNumber,
        String currentEtag,
        List<FileVersionResponse> versions) {
}
