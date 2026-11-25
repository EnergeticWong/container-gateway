package com.example.gateway.client;

/**
 * 网关返回的业务错误。{@code code} 是稳定契约，建议客户端按它分支处理。
 */
public class ContainerGatewayException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final String requestId;

    public ContainerGatewayException(int httpStatus, String code, String message, String requestId) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.requestId = requestId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }

    @Override
    public String toString() {
        return "ContainerGatewayException{http=" + httpStatus + ", code=" + code
                + ", message=" + getMessage() + ", requestId=" + requestId + '}';
    }
}
