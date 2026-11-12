package com.cloudstorage.dto;

import java.util.List;

public record FolderContentsResponse(
        FolderResponse folder,
        List<FolderResponse> folders,
        List<FileResponse> files) {
}
