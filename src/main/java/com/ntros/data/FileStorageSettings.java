package com.ntros.data;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

public record FileStorageSettings(Path baseDir, Path tmpDir, Path readyDir, Path liveDir, DateTimeFormatter formatter) {}
