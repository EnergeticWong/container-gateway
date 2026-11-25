package com.example.gateway.web.dto;

import java.util.List;

/**
 * 文件上传结果。
 *
 * @param dest       实际解压到的容器内目录
 * @param fileCount  普通文件数量
 * @param totalBytes 文件字节总数
 * @param files      条目路径清单
 */
public record FileUploadResult(String dest, int fileCount, long totalBytes, List<String> files) {
}
