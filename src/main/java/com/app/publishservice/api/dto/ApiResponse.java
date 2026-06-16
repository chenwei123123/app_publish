package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "通用接口响应")
public record ApiResponse<T>(
        @Schema(description = "是否成功") boolean success,
        @Schema(description = "响应数据") T data,
        @Schema(description = "响应消息") String message
) {

    /**
     * 处理success相关逻辑。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, "OK");
    }

    /**
     * 处理success相关逻辑。
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message);
    }

    /**
     * 处理failure相关逻辑。
     */
    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
