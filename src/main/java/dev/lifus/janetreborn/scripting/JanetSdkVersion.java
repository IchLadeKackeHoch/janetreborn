package dev.lifus.janetreborn.scripting;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record JanetSdkVersion(int major, int minor, int patch)
    implements Comparable<JanetSdkVersion> {
  public static final JanetSdkVersion CURRENT = new JanetSdkVersion(1, 0, 0);
  private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

  public JanetSdkVersion {
    if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("Negative version");
  }

  public static JanetSdkVersion parse(String value) {
    Matcher matcher = VERSION.matcher(Objects.requireNonNull(value, "version").trim());
    if (!matcher.matches()) throw new IllegalArgumentException("Invalid SDK version: " + value);
    return new JanetSdkVersion(
        Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)),
        matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
  }

  public boolean satisfies(String requirement) {
    String requested = Objects.requireNonNull(requirement, "requirement").trim();
    if (requested.startsWith("^")) {
      JanetSdkVersion minimum = parse(requested.substring(1));
      return compareTo(minimum) >= 0 && major == minimum.major;
    }
    if (requested.startsWith(">=")) {
      String[] terms = requested.split("\\s+");
      for (String term : terms) {
        if (term.startsWith(">=") && compareTo(parse(term.substring(2))) < 0) return false;
        if (term.startsWith("<") && compareTo(parse(term.substring(1))) >= 0) return false;
      }
      return true;
    }
    JanetSdkVersion exact = parse(requested);
    return major == exact.major && minor == exact.minor && patch >= exact.patch;
  }

  @Override
  public int compareTo(JanetSdkVersion other) {
    int result = Integer.compare(major, other.major);
    if (result == 0) result = Integer.compare(minor, other.minor);
    if (result == 0) result = Integer.compare(patch, other.patch);
    return result;
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
