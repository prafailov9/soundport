package com.ntros.data;

import java.io.InputStream;

/**
 * @param streamId the page's id for the live stream recorded alongside this upload; null when the
 *     page sent none
 */
public record UploadRequest(InputStream is, String contentType, String streamId) {}
