package me.sattle.checkout.internal.json;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON as every implementation reads and writes it (SPEC.md §2): read as JavaScript's {@code
 * JSON.parse} reads it, written as {@code JSON.stringify} writes it (no whitespace, members in the
 * order given, non-ASCII characters as they are, numbers in ECMAScript's spelling).
 */
public final class Json {
  private Json() {}

  /**
   * The value of {@code text}.
   *
   * @param text JSON text
   * @return its value
   * @throws JsonException when it is not JSON
   */
  public static JsonValue parse(String text) throws JsonException {
    return JsonParser.parse(text);
  }

  /**
   * The value of {@code text}, or null when it is not JSON (never repaired).
   *
   * @param text JSON text, or null
   * @return its value, or null
   */
  public static JsonValue parseOrNull(String text) {
    if (text == null) return null;
    try {
      return JsonParser.parse(text);
    } catch (JsonException e) {
      return null;
    }
  }

  /**
   * The value of an HTTP body (SPEC.md §15): one leading byte order mark ignored (as WHATWG's UTF-8
   * decode ignores it), the rest strict UTF-8 and JSON; null when it is not.
   *
   * @param body the body's bytes
   * @return its value, or null
   */
  public static JsonValue parseBody(byte[] body) {
    return parseOrNull(bodyText(body));
  }

  /**
   * The text of an HTTP body: one leading byte order mark (EF BB BF) dropped, the rest strict UTF-8
   * (a second mark stays, as U+FEFF); null when it is not UTF-8.
   *
   * @param body the body's bytes
   * @return its text, or null
   */
  public static String bodyText(byte[] body) {
    int skip =
        body.length >= 3
                && (body[0] & 0xff) == 0xef
                && (body[1] & 0xff) == 0xbb
                && (body[2] & 0xff) == 0xbf
            ? 3
            : 0;
    return strictUtf8(ByteBuffer.wrap(body, skip, body.length - skip));
  }

  /**
   * The text of UTF-8 {@code bytes}, decoded strictly (SPEC.md §6): null when they are not UTF-8,
   * never repaired; a byte order mark is a character (U+FEFF) like any other.
   *
   * @param bytes the bytes
   * @return the text, or null
   */
  public static String strictUtf8(byte[] bytes) {
    return strictUtf8(ByteBuffer.wrap(bytes));
  }

  private static String strictUtf8(ByteBuffer bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(bytes)
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  /**
   * {@code value} as {@code JSON.stringify} writes it.
   *
   * @param value a JSON value
   * @return its text
   */
  public static String write(JsonValue value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(JsonValue value, StringBuilder out) {
    if (value instanceof JsonObject) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, JsonValue> member : ((JsonObject) value).entries()) {
        if (!first) out.append(',');
        first = false;
        quote(member.getKey(), out);
        out.append(':');
        write(member.getValue(), out);
      }
      out.append('}');
    } else if (value instanceof JsonArray) {
      out.append('[');
      boolean first = true;
      for (JsonValue item : ((JsonArray) value).items()) {
        if (!first) out.append(',');
        first = false;
        write(item, out);
      }
      out.append(']');
    } else if (value instanceof JsonString) {
      quote(((JsonString) value).value(), out);
    } else if (value instanceof JsonNumber) {
      double number = ((JsonNumber) value).value();
      out.append(Double.isFinite(number) ? EcmaNumbers.format(number) : "null");
    } else if (value instanceof JsonBoolean) {
      out.append(value == JsonBoolean.TRUE ? "true" : "false");
    } else {
      out.append("null");
    }
  }

  /**
   * {@code text} as a JSON string, as {@code JSON.stringify} quotes it: {@code "} and {@code \}
   * escaped, the control characters as {@code \b \f \n \r \t} or a 4-digit {@code u} escape (in
   * lowercase hex), a lone surrogate as such an escape too, everything else as it is.
   *
   * @param text the text
   * @param out where it goes
   */
  public static void quote(String text, StringBuilder out) {
    out.append('"');
    int length = text.length();
    for (int i = 0; i < length; i += 1) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          continue;
        case '\\':
          out.append("\\\\");
          continue;
        case '\b':
          out.append("\\b");
          continue;
        case '\f':
          out.append("\\f");
          continue;
        case '\n':
          out.append("\\n");
          continue;
        case '\r':
          out.append("\\r");
          continue;
        case '\t':
          out.append("\\t");
          continue;
        default:
          break;
      }
      if (c < 0x20) {
        unicodeEscape(c, out);
      } else if (Character.isHighSurrogate(c)) {
        if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
          out.append(c).append(text.charAt(i + 1));
          i += 1;
        } else {
          unicodeEscape(c, out);
        }
      } else if (Character.isLowSurrogate(c)) {
        unicodeEscape(c, out);
      } else {
        out.append(c);
      }
    }
    out.append('"');
  }

  private static void unicodeEscape(char c, StringBuilder out) {
    String hex = Integer.toHexString(c);
    out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
  }

  /**
   * A JSON number of a safe integer.
   *
   * @param value an integer of at most 2^53 − 1 either way
   * @return its JSON number
   */
  public static JsonNumber number(long value) {
    if (Math.abs(value) > JsonNumber.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("Not a safe integer: " + value);
    }
    return new JsonNumber(value);
  }

  /**
   * The object of {@code value}.
   *
   * @param value a value, or null
   * @return it as an object, or null when it is not one
   */
  public static JsonObject object(JsonValue value) {
    return value instanceof JsonObject ? (JsonObject) value : null;
  }

  /**
   * The array of {@code value}.
   *
   * @param value a value, or null
   * @return it as an array, or null when it is not one
   */
  public static JsonArray array(JsonValue value) {
    return value instanceof JsonArray ? (JsonArray) value : null;
  }

  /**
   * The string of {@code value}.
   *
   * @param value a value, or null
   * @return its text, or null when it is not a string
   */
  public static String string(JsonValue value) {
    return value instanceof JsonString ? ((JsonString) value).value() : null;
  }

  /**
   * Whether {@code value} is a number.
   *
   * @param value a value, or null
   * @return true for a number (never a boolean)
   */
  public static boolean isNumber(JsonValue value) {
    return value instanceof JsonNumber;
  }

  /**
   * Whether {@code value} is an integer ({@code Number.isInteger}).
   *
   * @param value a value, or null
   * @return true for a whole number
   */
  public static boolean isInteger(JsonValue value) {
    return value instanceof JsonNumber && ((JsonNumber) value).isInteger();
  }

  /**
   * The safe integer of {@code value} ({@code Number.isSafeInteger}).
   *
   * @param value a value, or null
   * @return it, or null when it is not a safe integer
   */
  public static Long safeInteger(JsonValue value) {
    return value instanceof JsonNumber && ((JsonNumber) value).isSafeInteger()
        ? (long) ((JsonNumber) value).value()
        : null;
  }

  /**
   * The double of {@code value}.
   *
   * @param value a value, or null
   * @return it, or null when it is not a number
   */
  public static Double number(JsonValue value) {
    return value instanceof JsonNumber ? ((JsonNumber) value).value() : null;
  }

  /**
   * Whether {@code value} is {@code true}.
   *
   * @param value a value, or null
   * @return true for {@code true} alone
   */
  public static boolean isTrue(JsonValue value) {
    return value == JsonBoolean.TRUE;
  }

  /**
   * Whether {@code value} is a number greater than {@code bound} (a version above the one known).
   *
   * @param value a value, or null
   * @param bound the bound
   * @return true for a number above it
   */
  public static boolean isNumberAbove(JsonValue value, long bound) {
    return value instanceof JsonNumber && ((JsonNumber) value).value() > bound;
  }

  /**
   * Whether {@code value} is the number {@code expected} (whatever its spelling: 1, 1.0, 1e0).
   *
   * @param value a value, or null
   * @param expected the number
   * @return true when equal
   */
  public static boolean isNumber(JsonValue value, long expected) {
    return value instanceof JsonNumber && ((JsonNumber) value).value() == expected;
  }

  /**
   * The exact integer of {@code value}, of any size (a double holds one exactly when it is whole).
   *
   * @param value an integer
   * @return its value
   */
  public static BigInteger bigInteger(JsonNumber value) {
    return new java.math.BigDecimal(value.value()).toBigIntegerExact();
  }
}
