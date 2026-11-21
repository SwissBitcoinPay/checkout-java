package me.sattle.checkout.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.sattle.checkout.InvalidOptionException;
import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.SattlemeException;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonBoolean;
import me.sattle.checkout.internal.json.JsonException;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * JSON in tests: the pinned specification's files, and values written the short way. Read with the
 * package's own strict reader.
 */
public final class Js {
  private Js() {}

  /** The repository's root: Maven runs the tests there. */
  public static final Path ROOT = Path.of("").toAbsolutePath();

  /** The pinned copy of the specification. */
  public static final Path SPEC = ROOT.resolve("spec");

  /**
   * A file of the pinned specification, as JSON.
   *
   * @param path relative to {@code spec/}
   * @return its value
   */
  public static JsonValue spec(String path) {
    try {
      return Json.parse(Files.readString(SPEC.resolve(path), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (JsonException e) {
      throw new IllegalStateException(path + ": " + e.getMessage(), e);
    }
  }

  /**
   * {@code text} as JSON.
   *
   * @param text JSON text
   * @return its value
   */
  public static JsonValue parse(String text) {
    try {
      return Json.parse(text);
    } catch (JsonException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * {@code value} as JSON text.
   *
   * @param value a value
   * @return its text
   */
  public static String write(JsonValue value) {
    return Json.write(value);
  }

  /**
   * The JSON of a Java value: null, a boolean, a number, a string, a list, a map (in its order), or
   * a JSON value as it is.
   *
   * @param value the value
   * @return its JSON
   */
  public static JsonValue of(Object value) {
    if (value == null) return JsonNull.NULL;
    if (value instanceof JsonValue json) return json;
    if (value instanceof Boolean b) return JsonBoolean.of(b);
    if (value instanceof Number number) return new JsonNumber(number.doubleValue());
    if (value instanceof String text) return new JsonString(text);
    if (value instanceof List<?> list) {
      JsonArray array = new JsonArray();
      for (Object item : list) array.add(of(item));
      return array;
    }
    if (value instanceof Map<?, ?> map) {
      JsonObject object = new JsonObject();
      for (Map.Entry<?, ?> entry : map.entrySet())
        object.put((String) entry.getKey(), of(entry.getValue()));
      return object;
    }
    throw new IllegalArgumentException("Not JSON: " + value.getClass());
  }

  /**
   * An object of names and values, in order: {@code obj("a", 1, "b", "x")}.
   *
   * @param pairs names and values
   * @return the object
   */
  public static JsonObject obj(Object... pairs) {
    JsonObject object = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], of(pairs[i + 1]));
    return object;
  }

  /**
   * A copy of an object, members replaced or added in order: {@code {...value, a: 1}}.
   *
   * @param value an object
   * @param pairs names and values
   * @return the copy
   */
  public static JsonObject with(JsonValue value, Object... pairs) {
    JsonObject copy = new JsonObject();
    for (Map.Entry<String, JsonValue> entry : ((JsonObject) value).entries())
      copy.put(entry.getKey(), entry.getValue());
    for (int i = 0; i < pairs.length; i += 2) copy.put((String) pairs[i], of(pairs[i + 1]));
    return copy;
  }

  /**
   * An array of values.
   *
   * @param values the values
   * @return the array
   */
  public static JsonArray arr(Object... values) {
    JsonArray array = new JsonArray();
    for (Object value : values) array.add(of(value));
    return array;
  }

  /**
   * The member {@code name} of an object.
   *
   * @param value an object
   * @param name the name
   * @return its value, or null when absent
   */
  public static JsonValue get(JsonValue value, String name) {
    return ((JsonObject) value).get(name);
  }

  /**
   * The object at {@code name}.
   *
   * @param value an object
   * @param name the name
   * @return the object
   */
  public static JsonObject object(JsonValue value, String name) {
    return (JsonObject) get(value, name);
  }

  /**
   * The items of the array at {@code name}.
   *
   * @param value an object
   * @param name the name
   * @return the items
   */
  public static List<JsonValue> list(JsonValue value, String name) {
    return ((JsonArray) get(value, name)).items();
  }

  /**
   * The items of an array.
   *
   * @param value an array
   * @return the items
   */
  public static List<JsonValue> items(JsonValue value) {
    return ((JsonArray) value).items();
  }

  /**
   * The strings of the array at {@code name}.
   *
   * @param value an object
   * @param name the name
   * @return the strings
   */
  public static List<String> strings(JsonValue value, String name) {
    List<String> out = new ArrayList<>();
    for (JsonValue item : list(value, name)) out.add(((JsonString) item).value());
    return out;
  }

  /**
   * The string at {@code name}.
   *
   * @param value an object
   * @param name the name
   * @return the string, or null when it is not one
   */
  public static String text(JsonValue value, String name) {
    return Json.string(get(value, name));
  }

  /**
   * The number at {@code name}, as a long.
   *
   * @param value an object
   * @param name the name
   * @return the number
   */
  public static long integer(JsonValue value, String name) {
    return (long) ((JsonNumber) get(value, name)).value();
  }

  /**
   * Whether the member {@code name} is true.
   *
   * @param value an object
   * @param name the name
   * @return true for {@code true}
   */
  public static boolean bool(JsonValue value, String name) {
    return get(value, name) == JsonBoolean.TRUE;
  }

  /**
   * The bytes of the hex string at {@code name}.
   *
   * @param value an object
   * @param name the name
   * @return the bytes
   */
  public static byte[] hex(JsonValue value, String name) {
    return Hex.decode(text(value, name));
  }

  /** What may fail with an error of the package. */
  @FunctionalInterface
  public interface Run {
    /**
     * Runs.
     *
     * @return what it gives
     * @throws Exception when it fails
     */
    Object run() throws Exception;
  }

  /**
   * What {@code run} gives as JSON, or its error as the vectors write it: {@code {"error": code,
   * "param": param}}.
   *
   * @param run what to run
   * @return its outcome
   */
  public static JsonValue outcome(Run run) {
    try {
      return of(run.run());
    } catch (SattlemeException e) {
      String param =
          e instanceof InvalidOrderException order
              ? order.getParam()
              : e instanceof InvalidOptionException option ? option.getParam() : null;
      JsonObject error = obj("error", e.getCode());
      if (param != null) error.put("param", param);
      return error;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
