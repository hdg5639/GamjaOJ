package dev.gamjaoj;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(AccountException.class)
    ResponseEntity<?> account(AccountException error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식과 길이를 확인해 주세요."));
    }
}
