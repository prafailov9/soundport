package com.ntros.fileprocessor;

import com.ntros.data.UploadRequest;
import com.ntros.data.UploadResponse;

@FunctionalInterface
public interface FileDispatchingFunction {
  UploadResponse dispatch(UploadRequest uploadRequest);
}
