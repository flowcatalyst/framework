package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The value a push is built as (push contract section 1); the vectors are run by framework-push. */
class PushTest {

  record Order(String orderId, int version) {}

  static String reason(Throwable t) {
    assertThat(t).isInstanceOf(InvalidPushException.class);
    return ((InvalidPushException) t).reason();
  }

  @Test
  void dataFromAMapIsCompactWithSortedKeys() {
    Push p = Push.of("fulfil:order:1", "order.updated", Map.of("z", 1, "a", List.of(1, 2), "m", Map.of("y", true, "b", "x")));
    assertThat(p.data()).isEqualTo("{\"a\":[1,2],\"m\":{\"b\":\"x\",\"y\":true},\"z\":1}");
  }

  @Test
  void dataFromARecordKeepsTheDeclarationOrder() {
    assertThat(Push.of("fulfil:order:1", "order.updated", new Order("ord_1", 7)).data()).isEqualTo("{\"orderId\":\"ord_1\",\"version\":7}");
  }

  @Test
  void jsonTextIsKeptAsWrittenApartFromWhitespaceOutsideStrings() {
    Push p = Push.ofJson("fulfil:order:1", "order.updated", "{ \"b\" : \"a  b\\\" c\" ,\n \"a\" : 1.50e2 }");
    assertThat(p.data()).isEqualTo("{\"b\":\"a  b\\\" c\",\"a\":1.50e2}");
  }

  @Test
  void dataThatIsNotAnObjectOrNotJsonIsRefused() {
    assertThat(reason(catchThrowable(() -> Push.of("fulfil:order:1", "order.updated", List.of(1, 2))))).isEqualTo("DATA_NOT_OBJECT");
    assertThat(reason(catchThrowable(() -> Push.ofJson("fulfil:order:1", "order.updated", "\"text\"")))).isEqualTo("DATA_NOT_OBJECT");
    assertThat(reason(catchThrowable(() -> Push.ofJson("fulfil:order:1", "order.updated", "{\"a\":")))).isEqualTo("DATA_INVALID");
    assertThat(reason(catchThrowable(() -> Push.ofJson("fulfil:order:1", "order.updated", "{} {}")))).isEqualTo("DATA_INVALID");
    assertThat(reason(catchThrowable(() -> Push.ofJson("fulfil:order:1", "order.updated", "{\"a\":1,\"a\":2}")))).as("duplicate names").isEqualTo("DATA_INVALID");
  }

  @Test
  void theSizeLimitCountsUtf8BytesOfTheCompactText() {
    Map<String, String> big = new LinkedHashMap<>();
    big.put("s", "é".repeat(32_764)); // {"s":""} is 8 bytes; 2 bytes per é
    assertThat(Push.of("fulfil:order:1", "order.updated", big).data().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(8 + 65_528);
    big.put("s", "é".repeat(32_765));
    assertThat(reason(catchThrowable(() -> Push.of("fulfil:order:1", "order.updated", big)))).isEqualTo("DATA_TOO_LARGE");
  }

  @Test
  void topicAndTypeAreCheckedByTheConstructor() {
    assertThat(reason(catchThrowable(() -> new Push("Fulfil:order", "order.updated", "{}")))).isEqualTo("TOPIC_INVALID");
    assertThat(reason(catchThrowable(() -> new Push("fulfil:order", "reset", "{}")))).isEqualTo("TYPE_RESERVED");
    assertThat(reason(catchThrowable(() -> new Push("fulfil:order", "fw.anything", "{}")))).isEqualTo("TYPE_RESERVED");
    assertThat(new Push("fulfil:order", "reset.done", "{ }").data()).isEqualTo("{}");
  }

  @Test
  void anInvalidPushIsAnIllegalArgumentException() {
    assertThat(catchThrowable(() -> Push.of("fulfil", "x", Map.of()))).isInstanceOf(IllegalArgumentException.class);
  }
}
