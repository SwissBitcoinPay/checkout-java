package me.sattle.checkout.internal.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A strict JSON reader (RFC 8259), with the semantics of JavaScript's {@code JSON.parse} (SPEC.md
 * §2): of members with one name, the last counts; a number is a double whatever its spelling; a
 * string escape of a lone surrogate gives that lone surrogate, which a check of a text finds later
 * (SPEC.md §6). Whitespace is space, tab, line feed and carriage return. Iterative: however deep a
 * relay nests its arrays, the stack of the thread never overflows.
 */
final class JsonParser {
  private final String text;
  private int at;

  private JsonParser(String text) {
    this.text = text;
  }

  /** An open container while its values are read. */
  private abstract static class Frame {}

  private static final class ArrayFrame extends Frame {
    final JsonArray array = new JsonArray();
  }

  private static final class ObjectFrame extends Frame {
    final JsonObject object = new JsonObject();
    String name;
  }

  /** The deepest a text nests arrays and objects, counted together (SPEC.md §2). */
  static final int MAX_DEPTH = 64;

  static JsonValue parse(String text) throws JsonException {
    if (tooDeep(text)) throw new JsonException("Not JSON: nested more than " + MAX_DEPTH + " deep");
    JsonParser parser = new JsonParser(text);
    JsonValue value = parser.value();
    parser.whitespace();
    if (parser.at != text.length()) throw parser.error("text after the value");
    return value;
  }

  /**
   * Whether {@code text} opens more than 64 arrays and objects at once, outside its strings (a
   * backslash in a string escapes the next character): the pre-scan of SPEC.md §2, before any
   * reading, the same in every implementation. A text it refuses is no JSON, whatever else it is.
   */
  private static boolean tooDeep(String text) {
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      if (inString) {
        if (c == '\\') i += 1;
        else if (c == '"') inString = false;
      } else if (c == '"') {
        inString = true;
      } else if (c == '[' || c == '{') {
        depth += 1;
        if (depth > MAX_DEPTH) return true;
      } else if (c == ']' || c == '}') {
        depth -= 1;
      }
    }
    return false;
  }

  private JsonValue value() throws JsonException {
    Deque<Frame> stack = new ArrayDeque<>();
    for (; ; ) {
      whitespace();
      JsonValue value;
      char c = peek();
      if (c == '{') {
        at += 1;
        whitespace();
        if (peek() == '}') {
          at += 1;
          value = new JsonObject();
        } else {
          ObjectFrame frame = new ObjectFrame();
          frame.name = name();
          stack.push(frame);
          continue;
        }
      } else if (c == '[') {
        at += 1;
        whitespace();
        if (peek() == ']') {
          at += 1;
          value = new JsonArray();
        } else {
          stack.push(new ArrayFrame());
          continue;
        }
      } else {
        value = scalar(c);
      }
      // A value read: into its container, then the next value, or the container done.
      for (; ; ) {
        Frame frame = stack.peek();
        if (frame == null) return value;
        whitespace();
        char next = next();
        if (frame instanceof ArrayFrame) {
          JsonArray array = ((ArrayFrame) frame).array;
          array.add(value);
          if (next == ',') break;
          if (next != ']') throw error("expected , or ]");
          stack.pop();
          value = array;
        } else {
          ObjectFrame object = (ObjectFrame) frame;
          object.object.put(object.name, value);
          if (next == ',') {
            whitespace();
            object.name = name();
            break;
          }
          if (next != '}') throw error("expected , or }");
          stack.pop();
          value = object.object;
        }
      }
    }
  }

  /** A member's name and its colon. */
  private String name() throws JsonException {
    if (peek() != '"') throw error("expected a name");
    String name = string();
    whitespace();
    if (next() != ':') throw error("expected :");
    return name;
  }

  private JsonValue scalar(char c) throws JsonException {
    if (c == '"') return new JsonString(string());
    if (c == '-' || (c >= '0' && c <= '9')) return number();
    if (text.startsWith("true", at)) {
      at += 4;
      return JsonBoolean.TRUE;
    }
    if (text.startsWith("false", at)) {
      at += 5;
      return JsonBoolean.FALSE;
    }
    if (text.startsWith("null", at)) {
      at += 4;
      return JsonNull.NULL;
    }
    throw error("unexpected character");
  }

  private JsonNumber number() throws JsonException {
    int start = at;
    if (peek() == '-') at += 1;
    if (peek() == '0') {
      at += 1;
    } else if (isDigit(peek())) {
      while (isDigit(peek())) at += 1;
    } else {
      throw error("expected a digit");
    }
    if (peek() == '.') {
      at += 1;
      if (!isDigit(peek())) throw error("expected a digit after .");
      while (isDigit(peek())) at += 1;
    }
    if (peek() == 'e' || peek() == 'E') {
      at += 1;
      if (peek() == '+' || peek() == '-') at += 1;
      if (!isDigit(peek())) throw error("expected an exponent");
      while (isDigit(peek())) at += 1;
    }
    // Correctly rounded, as every JSON reader reads it (an exponent too large gives Infinity, as
    // in JavaScript).
    return new JsonNumber(Double.parseDouble(text.substring(start, at)));
  }

  private String string() throws JsonException {
    at += 1;
    StringBuilder out = null;
    int run = at;
    for (; ; ) {
      if (at >= text.length()) throw error("unterminated string");
      char c = text.charAt(at);
      if (c == '"') {
        String value = out == null ? text.substring(run, at) : out.append(text, run, at).toString();
        at += 1;
        return value;
      }
      if (c < 0x20) throw error("control character in a string");
      if (c != '\\') {
        at += 1;
        continue;
      }
      if (out == null) out = new StringBuilder();
      out.append(text, run, at);
      at += 1;
      if (at >= text.length()) throw error("unterminated escape");
      char escape = text.charAt(at++);
      switch (escape) {
        case '"':
          out.append('"');
          break;
        case '\\':
          out.append('\\');
          break;
        case '/':
          out.append('/');
          break;
        case 'b':
          out.append('\b');
          break;
        case 'f':
          out.append('\f');
          break;
        case 'n':
          out.append('\n');
          break;
        case 'r':
          out.append('\r');
          break;
        case 't':
          out.append('\t');
          break;
        case 'u':
          out.append(unicodeEscape());
          break;
        default:
          throw error("invalid escape");
      }
      run = at;
    }
  }

  /** The code unit of {@code \\uXXXX}: a lone surrogate stays one, as in JavaScript. */
  private char unicodeEscape() throws JsonException {
    if (at + 4 > text.length()) throw error("short \\u escape");
    int code = 0;
    for (int i = 0; i < 4; i += 1) {
      char c = text.charAt(at + i);
      int digit =
          c >= '0' && c <= '9'
              ? c - '0'
              : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
      if (digit < 0) throw error("invalid \\u escape");
      code = code * 16 + digit;
    }
    at += 4;
    return (char) code;
  }

  private void whitespace() {
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
      at += 1;
    }
  }

  private char peek() {
    return at < text.length() ? text.charAt(at) : '\0';
  }

  private char next() throws JsonException {
    if (at >= text.length()) throw error("unexpected end");
    return text.charAt(at++);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private JsonException error(String what) {
    return new JsonException("Not JSON at " + at + ": " + what);
  }
}
