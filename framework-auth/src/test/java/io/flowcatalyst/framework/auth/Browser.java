package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A browser for the tests: a cookie jar, the application in-process through {@link TestClient},
 * and the provider over real HTTP. Redirects are followed by the test, step by step.
 */
final class Browser {

  final Map<String, String> jar = new LinkedHashMap<>();
  private final TestClient app;
  private final String origin;
  private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  Browser(TestClient app, String origin) {
    this.app = app;
    this.origin = origin;
  }

  TestResponse get(String target, String... headers) {
    return send(app.request(Method.GET, target), null, headers);
  }

  TestResponse post(String target, String json, String... headers) {
    return send(app.request(Method.POST, target), json, headers);
  }

  TestResponse send(TestClient.Call call, String json, String... headers) {
    if (!jar.isEmpty()) {
      StringBuilder cookie = new StringBuilder();
      jar.forEach((k, v) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(k).append('=').append(v));
      call.header("Cookie", cookie.toString());
    }
    for (int i = 0; i < headers.length; i += 2) {
      call.header(headers[i], headers[i + 1]);
    }
    if (json != null) {
      call.jsonBody(json);
    }
    TestResponse response = call.send();
    absorb(response);
    return response;
  }

  void absorb(TestResponse response) {
    for (String setCookie : response.headerValues("Set-Cookie")) {
      String first = setCookie.split(";", 2)[0];
      int eq = first.indexOf('=');
      String name = first.substring(0, eq);
      String value = first.substring(eq + 1);
      if (value.isEmpty() || setCookie.contains("Max-Age=0")) {
        jar.remove(name);
      } else {
        jar.put(name, value);
      }
    }
  }

  /** Follows a provider URL once; returns its Location (the callback URL). */
  String atProvider(String url) {
    try {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 302) {
        throw new IllegalStateException("the provider answered " + r.statusCode() + ": " + r.body());
      }
      return r.headers().firstValue("Location").orElseThrow();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The application path of an absolute application URL. */
  String local(String url) {
    if (!url.startsWith(origin + "/")) {
      throw new IllegalStateException("not an application URL: " + url);
    }
    return url.substring(origin.length());
  }

  /** Starts a sign-in and returns the callback URL the provider sent the browser to. */
  String startSignIn(String returnTo) {
    TestResponse login = get("/auth/login?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8));
    if (login.status() != 302) {
      throw new IllegalStateException("login answered " + login.status() + ": " + login.body());
    }
    return atProvider(login.header("Location"));
  }

  /** A whole sign-in; returns the callback's answer. */
  TestResponse signIn(String returnTo) {
    return get(local(startSignIn(returnTo)));
  }
}
