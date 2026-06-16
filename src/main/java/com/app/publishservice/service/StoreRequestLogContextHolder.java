package com.app.publishservice.service;

public final class StoreRequestLogContextHolder {

    private static final ThreadLocal<Long> RELEASE_RECORD_ID_HOLDER = new ThreadLocal<>();

    /**
     * 初始化StoreRequestLogContextHolder。
     */
    private StoreRequestLogContextHolder() {
    }

    /**
     * 打开相关数据。
     */
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

    /**
     * 处理current 发布记录 Id相关逻辑。
     */
    public static Long currentReleaseRecordId() {
        return RELEASE_RECORD_ID_HOLDER.get();
    }

    public interface Scope extends AutoCloseable {
        /**
         * 关闭相关数据。
         */
        @Override
        void close();
    }
}
