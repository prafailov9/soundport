package com.ntros.data;

import java.nio.file.Path;

public record Job(State state, String text, Path file, String error) {}
