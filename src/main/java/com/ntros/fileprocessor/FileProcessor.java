package com.ntros.fileprocessor;

import com.ntros.data.UploadRequest;
import com.ntros.data.WriteFileResult;

public interface FileProcessor {
  WriteFileResult write(UploadRequest request);
}
