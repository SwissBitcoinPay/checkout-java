package me.sattle.checkout.internal;

/**
 * Text rules every implementation applies alike (SPEC.md §6): lengths in code points, case and
 * trimming of ASCII only, strict UTF-8, lone surrogates. Java's own {@code length()}, {@code
 * trim()} and {@code toLowerCase()} follow other rules (UTF-16 units, every control character, the
 * Kelvin sign lowered to {@code k}).
 */
public final class Text {
  private Text() {}

  /**
   * The length of {@code text} in code points (a lone surrogate counts as one).
   *
   * @param text the text
   * @return its code points
   */
  public static int codePointLength(String text) {
    return text.codePointCount(0, text.length());
  }

  /**
   * The bytes of {@code text} in UTF-8, a lone surrogate as U+FFFD (as JavaScript's {@code
   * TextEncoder} writes it).
   *
   * @param text the text
   * @return its UTF-8
   */
  public static byte[] utf8(String text) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length() + 16);
    int length = text.length();
    for (int i = 0; i < length; i += 1) {
      char c = text.charAt(i);
      int code = c;
      if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        code = Character.toCodePoint(c, text.charAt(i + 1));
        i += 1;
      } else if (Character.isSurrogate(c)) {
        code = 0xfffd;
      }
      if (code < 0x80) {
        out.write(code);
      } else if (code < 0x800) {
        out.write(0xc0 | (code >> 6));
        out.write(0x80 | (code & 0x3f));
      } else if (code < 0x10000) {
        out.write(0xe0 | (code >> 12));
        out.write(0x80 | ((code >> 6) & 0x3f));
        out.write(0x80 | (code & 0x3f));
      } else {
        out.write(0xf0 | (code >> 18));
        out.write(0x80 | ((code >> 12) & 0x3f));
        out.write(0x80 | ((code >> 6) & 0x3f));
        out.write(0x80 | (code & 0x3f));
      }
    }
    return out.toByteArray();
  }

  /**
   * The length of {@code text} in UTF-8 bytes.
   *
   * @param text the text
   * @return its bytes
   */
  public static int utf8Length(String text) {
    long bytes = 0;
    int length = text.length();
    for (int i = 0; i < length; i += 1) {
      char c = text.charAt(i);
      if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        bytes += 4;
        i += 1;
      } else {
        bytes += 3;
      }
    }
    return (int) Math.min(bytes, Integer.MAX_VALUE);
  }

  /**
   * {@code text} with A–Z lowered to a–z, and nothing else changed.
   *
   * @param text the text
   * @return it, ASCII letters lowered
   */
  public static String asciiLower(String text) {
    StringBuilder out = null;
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        if (out == null) out = new StringBuilder(text);
        out.setCharAt(i, (char) (c + 32));
      }
    }
    return out == null ? text : out.toString();
  }

  /**
   * Whether {@code c} is ASCII whitespace: space, tab, line feed, carriage return.
   *
   * @param c a character
   * @return true for ASCII whitespace
   */
  public static boolean isAsciiWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /**
   * {@code text} without its leading and trailing ASCII whitespace.
   *
   * @param text the text
   * @return it, trimmed
   */
  public static String trimAscii(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && isAsciiWhitespace(text.charAt(start))) start += 1;
    while (end > start && isAsciiWhitespace(text.charAt(end - 1))) end -= 1;
    return text.substring(start, end);
  }

  /**
   * {@code text} without its leading and trailing spaces (U+0020): a note's trimming.
   *
   * @param text the text
   * @return it, trimmed of spaces
   */
  public static String trimSpaces(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && text.charAt(start) == ' ') start += 1;
    while (end > start && text.charAt(end - 1) == ' ') end -= 1;
    return text.substring(start, end);
  }

  /**
   * Whether {@code text} holds a C0 control character (U+0000 to U+001F) or DEL (U+007F).
   *
   * @param text the text
   * @return true when it holds one
   */
  public static boolean hasControlCharacter(String text) {
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      if (c <= 0x1f || c == 0x7f) return true;
    }
    return false;
  }

  /**
   * Whether {@code text} is well-formed Unicode: no lone surrogate (a JSON escape makes one).
   *
   * @param text the text
   * @return true without a lone surrogate
   */
  public static boolean isWellFormed(String text) {
    int length = text.length();
    for (int i = 0; i < length; i += 1) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
          i += 1;
        } else {
          return false;
        }
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether {@code value} is {@code length} lowercase hex characters.
   *
   * @param value a text, or null
   * @param length the characters it must have
   * @return true when it is
   */
  public static boolean isLowerHex(String value, int length) {
    if (value == null || value.length() != length) return false;
    for (int i = 0; i < length; i += 1) {
      char c = value.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
  }

  /**
   * Whether {@code value} is hex32: 64 lowercase hex characters.
   *
   * @param value a text, or null
   * @return true when it is
   */
  public static boolean isHex32(String value) {
    return isLowerHex(value, 64);
  }

  /**
   * Whether {@code value} is 128 lowercase hex characters (a signature).
   *
   * @param value a text, or null
   * @return true when it is
   */
  public static boolean isHex64(String value) {
    return isLowerHex(value, 128);
  }

  /**
   * Whether {@code value} is 1 or more ASCII digits.
   *
   * @param value a text
   * @param max the most digits
   * @return true for 1 to {@code max} digits
   */
  public static boolean isDigits(String value, int max) {
    if (value == null || value.isEmpty() || value.length() > max) return false;
    for (int i = 0; i < value.length(); i += 1) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  /** The most UTF-16 units of an untrusted text an excerpt keeps. */
  private static final int EXCERPT_LENGTH = 200;

  /**
   * A text a server wrote (a relay's or a Lightning address's reason, an error of a rate source),
   * as a message holds it: a JSON string, so that no line break, control character or lone
   * surrogate of it reaches a log as it is, of 200 UTF-16 units at most (cut on a code point, then
   * an ellipsis).
   *
   * @param text the text
   * @return its excerpt, quoted
   */
  public static String excerpt(String text) {
    String cut = text;
    if (text.length() > EXCERPT_LENGTH) {
      int end = EXCERPT_LENGTH;
      if (Character.isHighSurrogate(text.charAt(end - 1))
          && Character.isLowSurrogate(text.charAt(end))) {
        end -= 1;
      }
      cut = text.substring(0, end) + "\u2026";
    }
    StringBuilder out = new StringBuilder();
    me.sattle.checkout.internal.json.Json.quote(cut, out);
    return out.toString();
  }
}
