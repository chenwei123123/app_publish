package com.app.publishservice.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "分页响应")
public record PageResponse<T>(
        @Schema(description = "当前页") long current,
        @Schema(description = "每页大小") long size,
        @Schema(description = "总记录数") long total,
        @Schema(description = "总页数") long pages,
        @Schema(description = "分页数据") List<T> records
) {

    public static <T> PageResponse<T> of(long current, long size, long total, List<T> records) {
        long safeSize = size <= 0 ? 1 : size;
        long calculatedPages = total <= 0 ? 0 : (total + safeSize - 1) / safeSize;
        return new PageResponse<>(current, size, total, calculatedPages, records);
    }
}
