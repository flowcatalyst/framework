package io.flowcatalyst.framework.http.jetty;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A bare HTTP/1.1 client over a socket, to control connections exactly. */
final class RawHttp implements Closeable {

  record Reply(int status, Map<String, String> headers, String body) {}

  private final Socket socket;
  private final InputStream in;

  RawHttp(int port) throws IOException {
    socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
    socket.setSoTimeout(10_000);
    in = socket.getInputStream();
  }

  void send(String request) throws IOException {
    socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
    socket.getOutputStream().flush();
  }

  void get(String path) throws IOException {
    send("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
  }

  Reply read() throws IOException {
    String statusLine = line();
    int status = Integer.parseInt(statusLine.split(" ")[1]);
    Map<String, String> headers = new LinkedHashMap<>();
    for (String l = line(); !l.isEmpty(); l = line()) {
      int c = l.indexOf(':');
      headers.put(l.substring(0, c).toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
    }
    String body = "";
    String len = headers.get("content-length");
    if (len != null) {
      byte[] b = in.readNBytes(Integer.parseInt(len));
      body = new String(b, StandardCharsets.UTF_8);
    }
    return new Reply(status, headers, body);
  }

  /** Whether the server has closed its side. */
  boolean isClosedByPeer() throws IOException {
    try {
      return in.read() < 0;
    } catch (java.net.SocketTimeoutException e) {
      return false;
    }
  }

  private String line() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) >= 0) {
      if (c == '\n') {
        break;
      }
      if (c != '\r') {
        out.write(c);
      }
    }
    if (c < 0 && out.size() == 0) {
      throw new IOException("connection closed");
    }
    return out.toString(StandardCharsets.ISO_8859_1);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
