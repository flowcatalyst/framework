package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What this module remembers for one request: cookies to set, and the session once read. */
final class RequestState {

  static final Request.Key<RequestState> KEY = Request.Key.of("fw.auth.state", RequestState.class);
  static final Request.Key<Authenticated> AUTHENTICATED = Request.Key.of("fw.auth.authenticated", Authenticated.class);

  private final List<String> cookies = new ArrayList<>();
  private @Nullable Optional<Authenticated> session;

  /** The state of a request, created when the middleware did not install one. */
  static RequestState of(Request request) {
    RequestState state = request.attribute(KEY);
    if (state == null) {
      state = new RequestState();
      request.attribute(KEY, state);
    }
    return state;
  }

  void setCookie(String value) {
    cookies.add(value);
  }

  @Nullable Optional<Authenticated> session() {
    return session;
  }

  void session(Optional<Authenticated> value) {
    this.session = value;
  }

  Response apply(Response response) {
    Response out = response;
    for (String cookie : cookies) {
      out = out.addHeader("Set-Cookie", cookie);
    }
    return out;
  }
}
