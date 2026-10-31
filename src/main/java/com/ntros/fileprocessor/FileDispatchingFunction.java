package com.ntros.fileprocessor;

import com.ntros.data.UploadRequest;

@FunctionalInterface
public interface FileDispatchingFunction {
  boolean writeAndDispatch(UploadRequest uploadRequest);
}
