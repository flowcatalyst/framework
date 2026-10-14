package io.flowcatalyst.framework.http.openapi;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Jackson annotations the emitter understands (contract section 5.5): {@code @JsonTypeInfo} and
 * {@code @JsonSubTypes} on sealed interfaces, {@code @JsonIgnore} on methods that are not record
 * components. Any other Jackson annotation on a walked type, record component or accessor would
 * make Jackson's JSON and the document disagree, so it is a dump problem.
 */
final class JacksonCheck {

  static final String JSON_TYPE_INFO = "com.fasterxml.jackson.annotation.JsonTypeInfo";
  static final String JSON_SUB_TYPES = "com.fasterxml.jackson.annotation.JsonSubTypes";
  static final String JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore";
  private static final String INSIDE = "com.fasterxml.jackson.annotation.JacksonAnnotationsInside";

  private JacksonCheck() {}

  /** Whether an annotation is Jackson's (or a combination annotation carrying Jackson's). */
  static boolean isJackson(Annotation a) {
    Class<? extends Annotation> type = a.annotationType();
    String name = type.getName();
    if (name.startsWith("com.fasterxml.jackson.") || name.startsWith("tools.jackson.")) {
      return true;
    }
    for (Annotation meta : type.getAnnotations()) {
      if (meta.annotationType().getName().equals(INSIDE)) {
        return true;
      }
    }
    return false;
  }

  static boolean has(AnnotatedElement e, String annotation) {
    for (Annotation a : e.getAnnotations()) {
      if (a.annotationType().getName().equals(annotation)) {
        return true;
      }
    }
    return false;
  }

  /** Every Jackson annotation on an element except the allowed ones, as "@Name". */
  static List<String> foreign(AnnotatedElement e, Set<String> allowed) {
    List<String> out = new ArrayList<>();
    for (Annotation a : e.getAnnotations()) {
      if (isJackson(a) && !allowed.contains(a.annotationType().getName())) {
        out.add("@" + a.annotationType().getSimpleName());
      }
    }
    return out;
  }

  /** The problems of a record: annotations on it and its components, getter-like methods. */
  static List<String> record(Class<?> type) {
    List<String> out = new ArrayList<>();
    for (String a : foreign(type, Set.of())) {
      out.add("Jackson annotation " + a + " on " + type.getSimpleName() + " is not understood by the document");
    }
    RecordComponent[] components = type.getRecordComponents();
    Set<String> accessors = new HashSet<>();
    Class<?>[] ctorTypes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      RecordComponent rc = components[i];
      ctorTypes[i] = rc.getType();
      accessors.add(rc.getName());
      // @JsonIgnore on a component is reported once, below, with what it would do
      List<String> found = new ArrayList<>(foreign(rc, Set.of(JSON_IGNORE)));
      Method accessor = rc.getAccessor();
      found.addAll(foreign(accessor, Set.of(JSON_IGNORE)));
      Field field = field(type, rc.getName());
      if (field != null) {
        found.addAll(foreign(field, Set.of(JSON_IGNORE)));
      }
      for (String a : new java.util.LinkedHashSet<>(found)) {
        out.add(
            "Jackson annotation " + a + " on component " + type.getSimpleName() + "." + rc.getName()
                + " is not understood by the document");
      }
    }
    try {
      Constructor<?> canonical = type.getDeclaredConstructor(ctorTypes);
      java.lang.reflect.Parameter[] params = canonical.getParameters();
      for (int i = 0; i < params.length; i++) {
        for (String a : foreign(params[i], Set.of(JSON_IGNORE))) {
          String text =
              "Jackson annotation " + a + " on component " + type.getSimpleName() + "." + components[i].getName()
                  + " is not understood by the document";
          if (!out.contains(text)) {
            out.add(text);
          }
        }
      }
    } catch (NoSuchMethodException e) {
      // every record has its canonical constructor
    }
    for (Method m : type.getMethods()) {
      if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.isSynthetic() || m.isBridge()) {
        continue;
      }
      if (m.getDeclaringClass() == Object.class || m.getDeclaringClass() == Record.class) {
        continue;
      }
      boolean component = accessors.contains(m.getName());
      if (component) {
        if (has(m, JSON_IGNORE)) {
          out.add("@JsonIgnore on component " + type.getSimpleName() + "." + m.getName() + " would drop it from the JSON");
        }
        continue;
      }
      for (String a : foreign(m, Set.of(JSON_IGNORE))) {
        out.add("Jackson annotation " + a + " on " + type.getSimpleName() + "." + m.getName() + "() is not understood by the document");
      }
      if (isGetterLike(m) && !has(m, JSON_IGNORE)) {
        out.add(
            type.getSimpleName() + "." + m.getName() + "() looks like a getter, so Jackson would write it as"
                + " a property the document does not have: rename it or mark it @JsonIgnore");
      }
    }
    return out;
  }

  /** The problems of an enum: annotations on it, its constants and its methods. */
  static List<String> enumType(Class<?> type) {
    List<String> out = new ArrayList<>();
    for (String a : foreign(type, Set.of())) {
      out.add("Jackson annotation " + a + " on enum " + type.getSimpleName() + " is not understood by the document");
    }
    for (Field f : type.getDeclaredFields()) {
      for (String a : foreign(f, Set.of())) {
        out.add("Jackson annotation " + a + " on " + type.getSimpleName() + "." + f.getName() + " is not understood by the document");
      }
    }
    for (Method m : type.getDeclaredMethods()) {
      for (String a : foreign(m, Set.of())) {
        out.add("Jackson annotation " + a + " on " + type.getSimpleName() + "." + m.getName() + "() is not understood by the document");
      }
    }
    return out;
  }

  /** The problems of a sealed interface's own methods (its type annotations are checked by the walker). */
  static List<String> sealedMethods(Class<?> type) {
    List<String> out = new ArrayList<>();
    for (Method m : type.getDeclaredMethods()) {
      for (String a : foreign(m, Set.of(JSON_IGNORE))) {
        out.add("Jackson annotation " + a + " on " + type.getSimpleName() + "." + m.getName() + "() is not understood by the document");
      }
    }
    return out;
  }

  /** What Jackson treats as a getter: {@code getX()} returning a value, {@code isX()} returning a boolean. */
  static boolean isGetterLike(Method m) {
    String n = m.getName();
    Class<?> r = m.getReturnType();
    if (r == void.class) {
      return false;
    }
    if (n.startsWith("get") && n.length() > 3) {
      return true;
    }
    return n.startsWith("is") && n.length() > 2 && (r == boolean.class || r == Boolean.class);
  }

  private static @Nullable Field field(Class<?> type, String name) {
    try {
      return type.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }
}
