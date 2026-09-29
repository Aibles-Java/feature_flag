package org.aibles.feature_flag.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import org.aibles.feature_flag.logging.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResourceNotFoundException.class)
  public ProblemDetail handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Not Found");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(DuplicateResourceException.class)
  public ProblemDetail handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  /**
   * A webhook URL rejected by the SSRF guard is a client input problem, so 400 — not 500. The
   * message names only the host the operator supplied, never what it resolved to.
   */
  @ExceptionHandler(WebhookUrlNotAllowedException.class)
  public ProblemDetail handleWebhookUrlNotAllowed(
      WebhookUrlNotAllowedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ProblemDetail handleInvalidRequest(
      InvalidRequestException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(InvalidRefreshTokenException.class)
  public ProblemDetail handleInvalidRefreshToken(
      InvalidRefreshTokenException ex, HttpServletRequest request) {
    // The specific reason (reuse detected, expired, revoked, disabled account) is a signal for the
    // defender, not the caller — it goes to the correlated server log. The client gets a single
    // coarse message so the endpoint cannot be probed to distinguish token states, honouring the
    // contract documented on InvalidRefreshTokenException. Reuse detection in particular is worth
    // alerting on server-side.
    log.warn("Refresh token rejected: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Unauthorized");
    problem.setDetail("Invalid or expired refresh token");
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ProblemDetail handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Bad Request");
    problem.setDetail(ex.getMessage());
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "Invalid value"));

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Validation Failed");
    problem.setDetail("One or more fields are invalid");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("errors", errors);
    return withRequestId(problem);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Internal Server Error");
    problem.setDetail("An unexpected error occurred");
    problem.setInstance(URI.create(request.getRequestURI()));
    return withRequestId(problem);
  }

  /**
   * Stamps the current request-correlation id onto the response body so support can tie an error a
   * user reports back to the exact log lines for that request (issue #28). The id is set on the MDC
   * by {@code RequestCorrelationFilter}; when absent (e.g. an error raised outside the servlet
   * filter chain) the property is simply omitted.
   */
  private ProblemDetail withRequestId(ProblemDetail problem) {
    String requestId = MDC.get(MdcKeys.REQUEST_ID);
    if (requestId != null) {
      problem.setProperty(MdcKeys.REQUEST_ID, requestId);
    }
    return problem;
  }
}
