package org.aibles.feature_flag.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-0.0 AC3: scans every test source/resource for real-looking emails and card numbers (PCI-DSS: no
 * real PAN or PII in tests). Reserved/synthetic domains (RFC 2606/6761) are the only ones allowed.
 */
class SyntheticDataHygieneTest {

  private static final Path TEST_ROOT = Path.of("src", "test");
  private static final Set<String> SYNTHETIC_DOMAINS =
      Set.of("example.test", "example.com", "example.org", "example.net");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");
  // 13-19 digits, optionally grouped by single spaces or dashes (typical PAN shapes)
  private static final Pattern CARD =
      Pattern.compile("(?<![\\w.-])\\d(?:[ -]?\\d){12,18}(?![\\w.-])");

  @Test
  @DisplayName("AC3: no non-synthetic email address in the test tree")
  void noRealEmails() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : testFiles()) {
      Matcher m = EMAIL.matcher(Files.readString(file));
      while (m.find()) {
        String domain = m.group(1).toLowerCase();
        if (!SYNTHETIC_DOMAINS.contains(domain)) {
          offenders.add(file + " -> " + m.group());
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("AC3: no Luhn-valid card-number-like digit string in the test tree")
  void noCardNumbers() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : testFiles()) {
      Matcher m = CARD.matcher(Files.readString(file));
      while (m.find()) {
        String digits = m.group().replaceAll("[ -]", "");
        if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
          offenders.add(file + " -> " + m.group());
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("the scanner itself flags a real-looking email and a Luhn-valid PAN")
  void scannerDetects() {
    assertThat(EMAIL.matcher("jane.doe@" + "gmail.com").find()).isTrue();
    assertThat(SYNTHETIC_DOMAINS).doesNotContain("gmail.com");
    // well-known public test PAN, built at runtime so this file holds no digit string
    String pan = "4" + "1".repeat(15);
    assertThat(luhn(pan)).isTrue();
    assertThat(luhn("1234567890123456")).isFalse();
  }

  private static List<Path> testFiles() throws IOException {
    try (Stream<Path> s = Files.walk(TEST_ROOT)) {
      return s.filter(Files::isRegularFile)
          .filter(
              p -> p.toString().matches(".*\\.(java|kt|properties|xml|json|sql|yml|yaml|csv|txt)"))
          .sorted()
          .toList();
    }
  }

  private static boolean luhn(String digits) {
    int sum = 0;
    boolean dbl = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (dbl) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      dbl = !dbl;
    }
    return sum % 10 == 0;
  }
}
