package com.example.gateway.service;

import com.example.gateway.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 审计口径：exec 读超时与容量失败必须能区分，且不影响对外错误码。 */
class ContainerServiceAuditTest {

    private static final GatewayException TIMEOUT = GatewayException.backendTimeout(
            "Docker 节点响应超时", new SocketTimeoutException("Read timed out"));

    @Test
    void execReadTimeoutIsAuditedAsExecTimeout() {
        assertEquals("EXEC_TIMEOUT", ContainerService.auditErrorCode(ContainerService.ACTION_EXEC, TIMEOUT));
        assertEquals("exec-timeout", ContainerService.failureKind(ContainerService.ACTION_EXEC, TIMEOUT));
        // 对外错误码不变
        assertEquals(GatewayException.NODE_UNAVAILABLE, TIMEOUT.getCode());
    }

    @Test
    void readTimeoutOnOtherActionsKeepsNodeUnavailableAuditCode() {
        assertEquals(GatewayException.NODE_UNAVAILABLE,
                ContainerService.auditErrorCode(ContainerService.ACTION_CREATE, TIMEOUT));
        assertEquals("backend-timeout",
                ContainerService.failureKind(ContainerService.ACTION_CREATE, TIMEOUT));
    }

    @Test
    void capacityAndUnreachableAreSeparated() {
        GatewayException capacity = GatewayException.nodeUnavailable(
                "所有 Docker 节点均已达到单节点并发上限 30，请稍后重试");
        GatewayException unreachable = GatewayException.nodeUnavailable("节点 w1 当前不可用，请稍后重试");
        assertEquals("capacity", ContainerService.failureKind(ContainerService.ACTION_CREATE, capacity));
        assertEquals("node-unreachable", ContainerService.failureKind(ContainerService.ACTION_CREATE, unreachable));
    }

    @Test
    void transportFailureAndBusinessError() {
        assertEquals("node-unreachable", ContainerService.failureKind(ContainerService.ACTION_EXEC,
                new RuntimeException("transport", new ConnectException("Connection refused"))));
        assertNull(ContainerService.failureKind(ContainerService.ACTION_EXEC,
                new IllegalArgumentException("bad request")));
    }
}
