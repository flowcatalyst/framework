package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A launch that fails with an Error (a NoClassDefFoundError out of a sync, say) must still close the
 * services it built. An HTTP client's event loops are non-daemon threads: left running, they keep the
 * JVM alive after main has died. The service here holds such a thread.
 */
class FailedLaunchTest {

  @TempDir Path dir;

  /** A service owning a non-daemon thread, as the Vert.x client does; closing it ends the thread. */
  static final class EventLoop implements AutoCloseable {
    private final CountDownLatch stop = new CountDownLatch(1);
    private final Thread thread;

    EventLoop() {
      thread =
          new Thread(
              () -> {
                try {
                  stop.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              },
              "test-event-loop");
      thread.setDaemon(false);
      thread.start();
    }

    boolean running() {
      return thread.isAlive();
    }

    @Override
    public void close() throws InterruptedException {
      stop.countDown();
      thread.join(5_000);
    }
  }

  private static final class Holder {
    volatile EventLoop loop;
  }

  private final Holder holder = new Holder();

  private AppBuilder builder(Mod module) {
    Mod withLoop =
        Mod.of("loop")
            .register(
                b ->
                    b.provide(
                        EventLoop.class,
                        lookup -> {
                          holder.loop = new EventLoop();
                          return holder.loop;
                        }));
    return App.builder().configDirectory(dir).environment(Map.of()).module(withLoop).module(module);
  }

  private interface Body {
    int run();
  }

  private static Command command(String name, Body body) {
    return new Command() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int run(App app, List<String> args, PrintStream out) {
        return body.run();
      }
    };
  }

  @Test
  void aCommandThatThrowsAnErrorExitsWithOneAndClosesTheServices() {
    Mod m = Mod.of("m").register(b -> b.command(command("sync", () -> {
      throw new NoClassDefFoundError("io/example/Missing");
    })));

    Launch launch = builder(m).launch("sync");

    assertThat(launch).isEqualTo(new Launch.Ran(1));
    assertThat(holder.loop.running()).isFalse();
  }

  @Test
  void aDeclarationCommandThatThrowsAnErrorExitsWithOne() {
    Mod m =
        Mod.of("m")
            .register(
                b ->
                    b.command(
                        new DeclarationCommand() {
                          @Override
                          public String name() {
                            return "decl";
                          }

                          @Override
                          public int run(Declarations declarations, List<String> args, PrintStream out) {
                            throw new NoClassDefFoundError("io/example/Missing");
                          }
                        }));

    assertThat(builder(m).launch("decl")).isEqualTo(new Launch.Ran(1));
  }

  @Test
  void anErrorOutOfBootClosesTheServicesAndPropagates() {
    Mod m = Mod.of("m").boot(a -> {
      throw new NoClassDefFoundError("io/example/Missing");
    });

    Throwable t = catchThrowable(() -> builder(m).launch());

    assertThat(t).isInstanceOf(NoClassDefFoundError.class);
    assertThat(holder.loop.running()).isFalse();
  }

  @Test
  void anErrorOutOfStartedClosesTheServicesAndPropagates() {
    Mod m = Mod.of("m").started(a -> {
      throw new NoClassDefFoundError("io/example/Missing");
    });

    Throwable t = catchThrowable(() -> builder(m).start());

    assertThat(t).isInstanceOf(NoClassDefFoundError.class);
    assertThat(holder.loop.running()).isFalse();
  }

  @Test
  void anErrorOutOfAProviderClosesTheServicesBuiltBeforeIt() {
    Mod m =
        Mod.of("m")
            .register(
                b ->
                    b.provide(
                        String.class,
                        lookup -> {
                          lookup.get(EventLoop.class);
                          throw new NoClassDefFoundError("io/example/Missing");
                        }));

    Throwable t = catchThrowable(() -> builder(m).start());

    assertThat(t).isInstanceOf(NoClassDefFoundError.class);
    assertThat(holder.loop.running()).isFalse();
  }
}
