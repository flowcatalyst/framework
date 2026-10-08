package io.flowcatalyst.framework.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class PipelineTest {

  private static Middleware<String, String> tag(String name, List<String> trace) {
    return (in, next) -> {
      trace.add(">" + name);
      String out = next.handle(in);
      trace.add("<" + name);
      return out + name;
    };
  }

  @Test
  void firstMiddlewareIsOutermost() throws Exception {
    List<String> trace = new ArrayList<>();
    Pipeline<String, String> p =
        Pipeline.of(
            List.of(tag("a", trace), tag("b", trace)),
            in -> {
              trace.add("handler");
              return "x";
            });
    assertThat(p.handle("in")).isEqualTo("xba");
    assertThat(trace).containsExactly(">a", ">b", "handler", "<b", "<a");
  }

  @Test
  void emptyListRunsTheTerminalOnly() throws Exception {
    assertThat(Pipeline.<String, String>of(List.of(), in -> in + "!").handle("hi")).isEqualTo("hi!");
  }

  @Test
  void aMiddlewareMayShortCircuit() throws Exception {
    List<String> trace = new ArrayList<>();
    Middleware<String, String> stop = (in, next) -> "stopped";
    Pipeline<String, String> p =
        Pipeline.of(
            List.of(tag("a", trace), stop, tag("c", trace)),
            in -> {
              trace.add("handler");
              return "x";
            });
    assertThat(p.handle("in")).isEqualTo("stoppeda");
    assertThat(trace).containsExactly(">a", "<a");
  }

  @Test
  void aMiddlewareMayReplaceTheInput() throws Exception {
    Middleware<String, String> upper = (in, next) -> next.handle(in.toUpperCase(Locale.ROOT));
    assertThat(Pipeline.of(List.of(upper), (String in) -> in + "?").handle("a")).isEqualTo("A?");
  }

  @Test
  void exceptionsPropagateThroughMiddlewareThatDoNotCatch() {
    List<String> trace = new ArrayList<>();
    Pipeline<String, String> p =
        Pipeline.of(
            List.of(tag("a", trace), tag("b", trace)),
            in -> {
              throw new IOException("boom");
            });
    assertThatThrownBy(() -> p.handle("in")).isInstanceOf(IOException.class).hasMessage("boom");
    assertThat(trace).containsExactly(">a", ">b");
  }

  @Test
  void anOuterMiddlewareCanCatchWhatAnInnerOneThrows() throws Exception {
    Middleware<String, String> catcher =
        (in, next) -> {
          try {
            return next.handle(in);
          } catch (IOException e) {
            return "caught " + e.getMessage();
          }
        };
    Pipeline<String, String> p =
        Pipeline.of(
            List.of(catcher),
            in -> {
              throw new IOException("boom");
            });
    assertThat(p.handle("in")).isEqualTo("caught boom");
  }

  @Test
  void theListIsCopied() throws Exception {
    List<Middleware<String, String>> list = new ArrayList<>();
    Pipeline<String, String> p = Pipeline.of(list, in -> "x");
    list.add((in, next) -> "changed");
    assertThat(p.handle("in")).isEqualTo("x");
  }

  @Test
  void pipelinesNest() throws Exception {
    List<String> trace = new ArrayList<>();
    Pipeline<String, String> inner = Pipeline.of(List.of(tag("b", trace)), in -> "x");
    Pipeline<String, String> outer = Pipeline.of(List.of(tag("a", trace)), inner);
    assertThat(outer.handle("in")).isEqualTo("xba");
  }
}
