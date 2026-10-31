package com.ntros.transcription;

import java.nio.file.Path;

public interface Transcriber {

  String transcribe(Path f);

}
