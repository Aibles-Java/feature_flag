package org.aibles.feature_flag.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.aibles.feature_flag.logging.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies {@link GlobalExceptionHandler} stamps the current request-correlation id onto error
 * responses (issue #28) so support can tie a reported error back to its log lines — and omits the
 * field cleanly when no correlation id is in scope.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void includesRequestIdFromMdcInErrorBody() {
    MDC.put(MdcKeys.REQUEST_ID, "req-42");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/flags/missing");

    ProblemDetail problem =
        handler.handleNotFound(new ResourceNotFoundException("Flag not found"), request);

    assertThat(problem.getProperties()).containsEntry(MdcKeys.REQUEST_ID, "req-42");
  }

  @Test
  void omitsRequestIdWhenNotInScope() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/flags/missing");

    ProblemDetail problem = handler.handleGeneric(new RuntimeException("boom"), request);

    // No MDC value set → the property is left off entirely rather than serialized as null.
    assertThat(
            problem.getProperties() == null
                || !problem.getProperties().containsKey(MdcKeys.REQUEST_ID))
        .isTrue();
  }
}
