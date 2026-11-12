package com.ntros.data;

import java.nio.file.Path;

public record UploadResponse(boolean success, UploadStatus uploadStatus, Path writeDir) {}
