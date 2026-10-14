package io.flowcatalyst.framework.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The removed-key and claimed-component checks (config-and-code contract, section 5). */
final class KeyChecks {

  private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9_-]*");
  private static final String GUIDE = " (see the guide page migrating-config-to-code)";

  private KeyChecks() {}

  static void check(
      KeyRules rules,
      Map<String, String> declaredBy,
      Map<String, Sources.Leaf> leaves,
      Map<String, String> env,
      List<ConfigProblem> problems) {
    Map<String, String> claimedBy = new LinkedHashMap<>();
    for (ComponentClaim claim : rules.claims()) {
      if (!COMPONENT.matcher(claim.component()).matches()) {
        problems.add(
            new ConfigProblem(
                claim.module(),
                claim.component(),
                null,
                "a claimed component must match [a-z][a-z0-9_-]*"));
        continue;
      }
      String earlier = claimedBy.putIfAbsent(claim.component(), claim.module());
      if (earlier != null) {
        problems.add(
            new ConfigProblem(
                claim.module(),
                claim.component(),
                null,
                "component '" + claim.component() + "' is already claimed by module '" + earlier + "'"));
      }
    }

    Set<String> reported = new HashSet<>();
    // Files: a key matching a removed pattern, else an undeclared key under a claimed component.
    for (Map.Entry<String, Sources.Leaf> e : leaves.entrySet()) {
      String key = e.getKey();
      if (declaredBy.containsKey(key) || isEmptySectionAboveDeclared(key, e.getValue(), declaredBy)) {
        continue;
      }
      @Nullable RemovedKey removed = firstMatch(rules.removed(), key);
      if (removed != null) {
        problems.add(movedTo(removed, key, e.getValue().source()));
        reported.add(key);
        continue;
      }
      String component = key.split("\\.", 2)[0];
      String claimer = claimedBy.get(component);
      if (claimer != null) {
        problems.add(
            new ConfigProblem(
                claimer,
                key,
                e.getValue().source(),
                "unknown key: component '"
                    + component
                    + "' belongs to module '"
                    + claimer
                    + "', which reads "
                    + declaredUnder(component, declaredBy)));
        reported.add(key);
      }
    }

    // Environment: names derived from removed patterns of two or more segments, with each '*'
    // replaced by the names found at that position in the files. Never scanned.
    for (RemovedKey removed : rules.removed()) {
      if (removed.segments().size() < 2) {
        continue;
      }
      for (String key : expand(removed.segments(), leaves.keySet())) {
        if (declaredBy.containsKey(key) || reported.contains(key)) {
          continue;
        }
        String name = EnvNames.of(key);
        if (env.get(name) != null) {
          problems.add(movedTo(removed, key, "environment variable " + name));
          reported.add(key);
        }
      }
    }
  }

  private static ConfigProblem movedTo(RemovedKey removed, String key, String source) {
    return new ConfigProblem(removed.module(), key, source, "moved to code: " + removed.movedTo() + GUIDE);
  }

  private static @Nullable RemovedKey firstMatch(List<RemovedKey> removed, String key) {
    for (RemovedKey r : removed) {
      if (r.matches(key)) {
        return r;
      }
    }
    return null;
  }

  /** An empty mapping (null value) whose key is an ancestor of a declared key: nothing to report. */
  private static boolean isEmptySectionAboveDeclared(
      String key, Sources.Leaf leaf, Map<String, String> declaredBy) {
    if (leaf.value() != null) {
      return false;
    }
    String prefix = key + ".";
    for (String declared : declaredBy.keySet()) {
      if (declared.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String declaredUnder(String component, Map<String, String> declaredBy) {
    Set<String> keys = new TreeSet<>();
    for (String declared : declaredBy.keySet()) {
      if (declared.startsWith(component + ".")) {
        keys.add(declared);
      }
    }
    return keys.isEmpty()
        ? "no key under it (its behaviour is set in code)"
        : "only " + String.join(", ", keys);
  }

  /** Every key the pattern names, with each '*' replaced by a name found there in the files. */
  private static List<String> expand(List<String> pattern, Set<String> fileKeys) {
    List<String> prefixes = new ArrayList<>();
    prefixes.add("");
    for (String segment : pattern) {
      List<String> next = new ArrayList<>();
      for (String prefix : prefixes) {
        if (!segment.equals("*")) {
          next.add(prefix.isEmpty() ? segment : prefix + "." + segment);
          continue;
        }
        Set<String> names = new LinkedHashSet<>();
        String head = prefix + ".";
        for (String k : fileKeys) {
          if (k.startsWith(head)) {
            String rest = k.substring(head.length());
            int dot = rest.indexOf('.');
            names.add(dot < 0 ? rest : rest.substring(0, dot));
          }
        }
        for (String name : names) {
          next.add(head + name);
        }
      }
      prefixes = next;
    }
    return prefixes;
  }
}
