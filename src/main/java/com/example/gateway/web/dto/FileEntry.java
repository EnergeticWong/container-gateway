package com.example.gateway.web.dto;

/**
 * 容器内文件条目（列举接口返回）。
 *
 * @param name 相对产物目录的路径
 * @param size 字节数（目录为 0）
 * @param type {@code file} 或 {@code dir}
 */
public record FileEntry(String name, long size, String type) {
}
