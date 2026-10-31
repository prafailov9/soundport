package com.ntros.data;

import java.io.InputStream;

public record UploadRequest(InputStream is, String contentType) {}
