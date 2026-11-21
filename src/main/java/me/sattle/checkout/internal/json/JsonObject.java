package me.sattle.checkout.internal.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A JSON object: its members in the order they were first given, as JavaScript keeps a member given
 * again in its place, with the last value.
 */
public final class JsonObject implements JsonValue {
  private final Map<String, JsonValue> members = new LinkedHashMap<>();

  /** An empty object. */
  public JsonObject() {}

  /**
   * Sets the member {@code name} (the last value of a name given twice counts, in the place of the
   * first).
   *
   * @param name its name
   * @param value its value
   * @return this object
   */
  public JsonObject put(String name, JsonValue value) {
    members.put(name, value);
    return this;
  }

  /**
   * Sets a string member, or null.
   *
   * @param name its name
   * @param value the string, or null
   * @return this object
   */
  public JsonObject put(String name, String value) {
    return put(name, value == null ? JsonNull.NULL : new JsonString(value));
  }

  /**
   * Sets an integer member.
   *
   * @param name its name
   * @param value a safe integer
   * @return this object
   */
  public JsonObject put(String name, long value) {
    return put(name, Json.number(value));
  }

  /**
   * Sets an integer member, or null.
   *
   * @param name its name
   * @param value a safe integer, or null
   * @return this object
   */
  public JsonObject put(String name, Long value) {
    return put(name, value == null ? JsonNull.NULL : Json.number(value));
  }

  /**
   * Sets a boolean member.
   *
   * @param name its name
   * @param value the boolean
   * @return this object
   */
  public JsonObject put(String name, boolean value) {
    return put(name, JsonBoolean.of(value));
  }

  /**
   * The member {@code name}.
   *
   * @param name its name
   * @return its value, or null when it is absent ({@link JsonNull#NULL} when it is null)
   */
  public JsonValue get(String name) {
    return members.get(name);
  }

  /**
   * Whether the member {@code name} is present (null or not).
   *
   * @param name its name
   * @return true when present
   */
  public boolean has(String name) {
    return members.containsKey(name);
  }

  /**
   * How many members it holds.
   *
   * @return the count
   */
  public int size() {
    return members.size();
  }

  /**
   * Its members, in order.
   *
   * @return an unmodifiable view
   */
  public Set<Map.Entry<String, JsonValue>> entries() {
    return Collections.unmodifiableMap(members).entrySet();
  }

  /**
   * Its members' names, in order.
   *
   * @return an unmodifiable view
   */
  public Set<String> names() {
    return Collections.unmodifiableSet(members.keySet());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonObject && ((JsonObject) other).members.equals(members);
  }

  @Override
  public int hashCode() {
    return members.hashCode();
  }

  @Override
  public String toString() {
    return Json.write(this);
  }
}
