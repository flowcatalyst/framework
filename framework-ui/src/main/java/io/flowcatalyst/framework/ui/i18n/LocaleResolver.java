package io.flowcatalyst.framework.ui.i18n;

import io.flowcatalyst.framework.http.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the locale of a request (contract section 3): the user's preference, the tenant's
 * default, {@code Accept-Language} by quality, then {@code en}, keeping the first that names a
 * language the application supports. The result is one of the supported tags: the one equal to the
 * candidate, else the first supported tag of the candidate's language.
 */
public final class LocaleResolver {

  private final List<String> supported;
  private final @Nullable Function<Request, @Nullable String> user;
  private final @Nullable Function<Request, @Nullable String> tenant;

  private LocaleResolver(Builder b) {
    List<String> sup = new ArrayList<>();
    for (String t : b.supported) {
      String n = Locales.normalize(t);
      if (!n.isEmpty() && !sup.contains(n)) {
        sup.add(n);
      }
    }
    if (!sup.contains(Locales.DEFAULT)) {
      sup.add(Locales.DEFAULT);
    }
    this.supported = List.copyOf(sup);
    this.user = b.user;
    this.tenant = b.tenant;
  }

  /**
   * Starts a builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link LocaleResolver}. */
  public static final class Builder {
    private final List<String> supported = new ArrayList<>();
    private @Nullable Function<Request, @Nullable String> user;
    private @Nullable Function<Request, @Nullable String> tenant;

    private Builder() {}

    /**
     * The tags the application ships ({@code en} is always added).
     *
     * @param tags BCP 47 tags
     * @return this
     */
    public Builder supported(String... tags) {
      supported.addAll(List.of(tags));
      return this;
    }

    /**
     * The user's preference (for FlowCatalyst applications, the principal's locale).
     *
     * @param user a function of the request returning a tag, or null for none
     * @return this
     */
    public Builder user(Function<Request, @Nullable String> user) {
      this.user = user;
      return this;
    }

    /**
     * The tenant's default (the client's default locale).
     *
     * @param tenant a function of the request returning a tag, or null for none
     * @return this
     */
    public Builder tenant(Function<Request, @Nullable String> tenant) {
      this.tenant = tenant;
      return this;
    }

    /**
     * Builds the resolver.
     *
     * @return the resolver
     */
    public LocaleResolver build() {
      return new LocaleResolver(this);
    }
  }

  /**
   * A resolver that supports only English.
   *
   * @return the resolver
   */
  public static LocaleResolver englishOnly() {
    return builder().build();
  }

  /**
   * The tags the resolver can return.
   *
   * @return the supported tags, {@code en} included
   */
  public List<String> supported() {
    return supported;
  }

  /**
   * The supported tag a candidate names: the full tag, then its language.
   *
   * @param candidate a tag, or null
   * @return the supported tag, or null for none
   */
  public @Nullable String match(@Nullable String candidate) {
    String tag = Locales.normalize(candidate);
    if (tag.isEmpty()) {
      return null;
    }
    if (supported.contains(tag)) {
      return tag;
    }
    String lang = Locales.language(tag);
    for (String s : supported) {
      if (Locales.language(s).equals(lang)) {
        return s;
      }
    }
    return null;
  }

  /**
   * The locale of a request.
   *
   * @param request the request
   * @return a supported tag
   */
  public String resolve(Request request) {
    return resolve(
        user == null ? null : user.apply(request),
        tenant == null ? null : tenant.apply(request),
        request.header("Accept-Language"));
  }

  /**
   * The locale for the three inputs.
   *
   * @param userTag the user's preference, or null
   * @param tenantTag the tenant's default, or null
   * @param acceptLanguage the {@code Accept-Language} header, or null
   * @return a supported tag
   */
  public String resolve(@Nullable String userTag, @Nullable String tenantTag, @Nullable String acceptLanguage) {
    String t = match(userTag);
    if (t != null) {
      return t;
    }
    t = match(tenantTag);
    if (t != null) {
      return t;
    }
    for (String c : Locales.parseAcceptLanguage(acceptLanguage)) {
      t = match(c);
      if (t != null) {
        return t;
      }
    }
    return Locales.DEFAULT;
  }
}
