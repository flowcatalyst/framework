package io.flowcatalyst.framework.partitions;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forwards PostgreSQL connections and counts the statements that run (Bind and simple Query messages on the
 * wire), so a test can show a read's query count. The client must not use TLS ({@code sslmode=disable}).
 */
final class CountingProxy implements AutoCloseable {

  private final ServerSocket server;
  private final String targetHost;
  private final int targetPort;
  final AtomicLong executions = new AtomicLong();

  CountingProxy(String targetHost, int targetPort) throws IOException {
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    Thread.ofVirtual().name("counting-proxy").start(this::acceptLoop);
  }

  int port() {
    return server.getLocalPort();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket client = server.accept();
        Thread.ofVirtual().start(() -> serve(client));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket client) {
    try (client;
        Socket upstream = new Socket(targetHost, targetPort)) {
      Thread.ofVirtual().start(() -> copy(upstream, client));
      DataInputStream in = new DataInputStream(client.getInputStream());
      OutputStream out = upstream.getOutputStream();
      // the start-up message has no type byte: a length that includes itself
      int length = in.readInt();
      byte[] startup = new byte[length - 4];
      in.readFully(startup);
      out.write(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
      out.write(startup);
      while (true) {
        int type = in.read();
        if (type < 0) {
          return;
        }
        int len = in.readInt();
        byte[] body = new byte[len - 4];
        in.readFully(body);
        if (type == 'B' || type == 'Q') {
          executions.incrementAndGet();
        }
        out.write(type);
        out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
        out.write(body);
        out.flush();
      }
    } catch (IOException e) {
      // a closed connection ends the forwarding
    }
  }

  private static void copy(Socket from, Socket to) {
    try {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) >= 0) {
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException e) {
      // a closed connection ends the forwarding
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
