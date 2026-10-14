package com.tramo.backend.exception;

public class StorageQuotaExceededException extends LimitExceededException {
    public StorageQuotaExceededException() {
        super("Storage quota exceeded. Remove content or upgrade your plan.");
    }
}
