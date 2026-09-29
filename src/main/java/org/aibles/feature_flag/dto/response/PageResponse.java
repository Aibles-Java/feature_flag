package org.aibles.feature_flag.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope for admin list endpoints (issue #33).
 *
 * <p>Deliberately a hand-rolled, minimal shape — {@code content} plus the four counters — rather
 * than serializing Spring Data's {@link Page} directly, whose JSON form is verbose and not
 * API-contract-stable across Spring versions. The SDK evaluation endpoint is intentionally NOT
 * wrapped (SDKs need the full flag set in one call).
 *
 * @param <T> the element type of {@link #content}
 */
@Data
@Builder
public class PageResponse<T> {

  /** The page of results (never null; empty when the page is past the end). */
  private List<T> content;

  /** Zero-based index of this page. */
  private int page;

  /** Number of elements requested per page (after the max-size clamp). */
  private int size;

  /** Total number of elements across all pages. */
  private long totalElements;

  /** Total number of pages available. */
  private int totalPages;

  /**
   * Wraps a Spring Data {@link Page} (already mapped to the response DTO type) in this envelope.
   */
  public static <T> PageResponse<T> from(Page<T> page) {
    return PageResponse.<T>builder()
        .content(page.getContent())
        .page(page.getNumber())
        .size(page.getSize())
        .totalElements(page.getTotalElements())
        .totalPages(page.getTotalPages())
        .build();
  }
}
