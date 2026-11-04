package com.pnas.agent.remote;

/** 服务端返回非 2xx 时抛出的远程调用异常。 */
public class AgentException extends RuntimeException {

    private final int status;

    public AgentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
