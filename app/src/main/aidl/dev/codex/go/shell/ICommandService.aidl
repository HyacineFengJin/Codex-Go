package dev.codex.go.shell;

interface ICommandService {
    String execute(String requestId, String command, long timeoutMs) = 0;
    void cancel(String requestId) = 1;
    void destroy() = 16777114;
}
