package com.example.gateway.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * start / stop / restart 的轻量返回。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusView(String id, String status) {
}
