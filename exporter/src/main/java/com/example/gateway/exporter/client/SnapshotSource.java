package com.example.gateway.exporter.client;

import com.fasterxml.jackson.databind.JsonNode;

/** 快照来源抽象，便于测试替换。 */
public interface SnapshotSource {
    JsonNode fetch(long budgetMs) throws Exception;
}
