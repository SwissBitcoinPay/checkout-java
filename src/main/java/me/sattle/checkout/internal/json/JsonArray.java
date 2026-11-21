package me.sattle.checkout.internal.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A JSON array. */
public final class JsonArray implements JsonValue {
  private final List<JsonValue> items = new ArrayList<>();

  /** An empty array. */
  public JsonArray() {}

  /**
   * An array of {@code values}.
   *
   * @param values its items
   * @return the array
   */
  public static JsonArray of(List<? extends JsonValue> values) {
    JsonArray array = new JsonArray();
    for (JsonValue value : values) array.add(value);
    return array;
  }

  /**
   * An array of strings.
   *
   * @param values the strings
   * @return the array
   */
  public static JsonArray ofStrings(List<String> values) {
    JsonArray array = new JsonArray();
    for (String value : values) array.add(value);
    return array;
  }

  /**
   * Appends {@code value}.
   *
   * @param value the item
   * @return this array
   */
  public JsonArray add(JsonValue value) {
    items.add(value);
    return this;
  }

  /**
   * Appends a string, or null.
   *
   * @param value the string, or null
   * @return this array
   */
  public JsonArray add(String value) {
    return add(value == null ? JsonNull.NULL : new JsonString(value));
  }

  /**
   * Appends an integer.
   *
   * @param value a safe integer
   * @return this array
   */
  public JsonArray add(long value) {
    return add(Json.number(value));
  }

  /**
   * The item at {@code index}.
   *
   * @param index the index
   * @return the item
   */
  public JsonValue get(int index) {
    return items.get(index);
  }

  /**
   * How many items it holds.
   *
   * @return the count
   */
  public int size() {
    return items.size();
  }

  /**
   * Its items, in order.
   *
   * @return an unmodifiable view
   */
  public List<JsonValue> items() {
    return Collections.unmodifiableList(items);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JsonArray && ((JsonArray) other).items.equals(items);
  }

  @Override
  public int hashCode() {
    return items.hashCode();
  }

  @Override
  public String toString() {
    return Json.write(this);
  }
}
