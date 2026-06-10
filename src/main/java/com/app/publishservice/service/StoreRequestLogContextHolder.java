package com.app.publishservice.service;

public final class StoreRequestLogContextHolder {

    private static final ThreadLocal<Long> RELEASE_RECORD_ID_HOLDER = new ThreadLocal<>();

    private StoreRequestLogContextHolder() {
    }

    public static Scope open(Long releaseRecordId) {
        Long previous = RELEASE_RECORD_ID_HOLDER.get();
        if (releaseRecordId == null) {
            RELEASE_RECORD_ID_HOLDER.remove();
        } else {
            RELEASE_RECORD_ID_HOLDER.set(releaseRecordId);
        }
        return () -> {
            if (previous == null) {
                RELEASE_RECORD_ID_HOLDER.remove();
            } else {
                RELEASE_RECORD_ID_HOLDER.set(previous);
            }
        };
    }

    public static Long currentReleaseRecordId() {
        return RELEASE_RECORD_ID_HOLDER.get();
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
