package com.example.gateway.docker;

import com.example.gateway.error.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerOpsTransportFailureTest {

    @Test
    void detectsWrappedIoException() {
        RuntimeException wrapped = new RuntimeException("transport", new ConnectException("Connection refused"));
        assertTrue(DockerOps.isTransportFailure(wrapped));
    }

    @Test
    void detectsDirectIoException() {
        assertTrue(DockerOps.isTransportFailure(new IOException("broken pipe")));
    }

    @Test
    void ignoresBusinessException() {
        assertFalse(DockerOps.isTransportFailure(new IllegalArgumentException("bad request")));
    }

    @Test
    void detectsReadTimeoutButNotConnectFailure() {
        RuntimeException timeout = new RuntimeException("transport",
                new SocketTimeoutException("Read timed out"));
        assertTrue(DockerOps.isReadTimeout(timeout));
        assertFalse(DockerOps.isReadTimeout(new RuntimeException(new ConnectException("Connection refused"))));
    }

    @Test
    void backendTimeoutKeepsHttp503AndNodeUnavailableCode() {
        GatewayException e = GatewayException.backendTimeout("Docker 节点响应超时",
                new SocketTimeoutException("Read timed out"));
        assertTrue(e instanceof GatewayException.BackendTimeoutException);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(GatewayException.NODE_UNAVAILABLE, e.getCode());
    }
}
