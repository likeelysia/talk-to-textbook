package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.exception.OverloadedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理:把异常翻译成"规范的 JSON 错误 + 合适的 HTTP 状态码"。
 *
 * <p>没有它,异常会变成一大坨 Tomcat 错误页/堆栈 —— 客户端根本没法解析。
 * 有了它,客户端永远收到 {@code {"error":"..."}}。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 资源繁忙(模型并发/排队满)→ 503,让前端提示"稍后再试"。 */
    @ExceptionHandler(OverloadedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onOverloaded(OverloadedException e) {
        log.warn("服务繁忙:{}", e.getMessage());
        return Map.of("error", e.getMessage() == null ? "服务繁忙,请稍后重试" : e.getMessage());
    }

    /** 业务异常(参数不对、模型报错等)→ 400。 */
    @ExceptionHandler(AgentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onAgentException(AgentException e) {
        log.warn("业务异常:{}", e.getMessage());
        return Map.of("error", e.getMessage() == null ? "业务异常" : e.getMessage());
    }

    /** 其他异常:框架自己的异常(405/415/404 等)保留它自带的状态码,真未预期的才 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onOtherException(Exception e) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (e instanceof ErrorResponse errorResponse) {           // Spring 6:参数/媒体类型不对等自带状态码
            status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
        }
        if (status.is5xxServerError()) {
            log.error("未预期异常", e);
        } else {
            log.warn("请求异常({}):{}", status.value(), e.getMessage());
        }
        return ResponseEntity.status(status)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
