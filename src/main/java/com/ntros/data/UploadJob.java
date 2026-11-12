package com.ntros.data;

import java.nio.file.Path;

/**
 * An uploaded recording waiting to be transcribed.
 *
 * @param streamId the live stream recorded alongside it, or null if there was none
 */
public record UploadJob(Path file,String jobId ,String streamId, Command command) {}
