package io.flowcatalyst.framework.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * Serves a bound {@link Operation} (OpenAPI contract section 3): the permission, then the
 * parameters, then the body, then the handler; the outcome is written with the typed-operation
 * mapper.
 */
final class OperationRoute {

  private static final Logger LOG = LoggerFactory.getLogger(OperationRoute.class);

  /** Operation id and status pairs already warned about, so each is logged once. */
  static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  private OperationRoute() {}

  static <B, R> Handler handler(Operation<B, R> op, OperationHandler<B, R> handler) {
    Set<Integer> documented = op.documentedErrorStatuses();
    return request -> {
      String permission = op.permission();
      if (permission != null && !request.context().hasPermission(permission)) {
        return HttpErrors.forbidden("FORBIDDEN", "this needs the permission " + permission);
      }
      List<FieldError> errors = new ArrayList<>();
      Map<Object, @Nullable Object> values = new HashMap<>();
      for (PathParam<?> p : op.pathParams()) {
        values.put(p, ParamValues.parse(p.type(), p.name(), request.pathParam(p.name()), errors));
      }
      for (QueryParam<?> q : op.queryParams()) {
        values.put(q, q.read(request, errors));
      }
      if (!errors.isEmpty()) {
        throw new ValidationException(errors);
      }
      Validator<B> validator = op.body();
      @SuppressWarnings("unchecked")
      B body = validator == null ? (B) NoBody.VALUE : request.json(validator, Json.operationMapper());
      Outcome<R> outcome = handler.handle(new OperationRequest<>(request, body, values));
      if (outcome == null) {
        throw new IllegalStateException("the handler of " + op.label() + " returned no outcome");
      }
      return switch (outcome) {
        case Outcome.Ok<R> ok -> {
          Response response;
          if (!op.hasResponseBody()) {
            response = Response.noContent();
          } else {
            byte[] json;
            try {
              json = Json.operationMapper().writeValueAsBytes(ok.value());
            } catch (JacksonException e) {
              throw new IllegalStateException("cannot encode the response of " + op.label(), e);
            }
            response = Response.shared(op.status(), "application/json", json);
          }
          for (Response.Header h : ok.headers()) {
            response = response.addHeader(h.name(), h.value());
          }
          yield response;
        }
        case Outcome.Failed<R> failed -> {
          int status = failed.error().httpStatus();
          if (!documented.contains(status) && WARNED.add(op.label() + " " + status)) {
            LOG.warn("status {} is not in the document for {}", status, op.label());
          }
          yield HttpErrors.of(failed.error());
        }
      };
    };
  }
}
