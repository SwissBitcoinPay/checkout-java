package me.sattle.checkout.internal;

import java.util.Base64;

/**
 * Base64 (RFC 4648) decoded strictly (SPEC.md §2): only the canonical encoding of some bytes is
 * accepted (no other alphabet, no whitespace, unused trailing bits zero, padding exactly where §4
 * puts it). The platform's decoders accept more: each text is decoded, encoded again and compared.
 */
public final class Base64Strict {
  private Base64Strict() {}

  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Encoder STANDARD_ENCODER = Base64.getEncoder();

  /**
   * Base64url without padding (RFC 4648 §5).
   *
   * @param bytes the bytes
   * @return their base64url
   */
  public static String encodeUrl(byte[] bytes) {
    return URL_ENCODER.encodeToString(bytes);
  }

  /**
   * Standard base64 with padding (RFC 4648 §4).
   *
   * @param bytes the bytes
   * @return their base64
   */
  public static String encodeStandard(byte[] bytes) {
    return STANDARD_ENCODER.encodeToString(bytes);
  }

  /**
   * The bytes of unpadded base64url text, or null when it is not their canonical encoding.
   *
   * @param text the text
   * @return the bytes, or null
   */
  public static byte[] decodeUrl(String text) {
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      boolean valid =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!valid) return null;
    }
    if (text.length() % 4 == 1) return null;
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return URL_ENCODER.encodeToString(bytes).equals(text) ? bytes : null;
  }

  /**
   * The bytes of padded standard base64 text, or null when it is not their canonical encoding.
   *
   * @param text the text
   * @return the bytes, or null
   */
  public static byte[] decodeStandard(String text) {
    if (text.length() % 4 != 0) return null;
    int padding = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
    for (int i = 0; i < text.length() - padding; i += 1) {
      char c = text.charAt(i);
      boolean valid =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '+'
              || c == '/';
      if (!valid) return null;
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return STANDARD_ENCODER.encodeToString(bytes).equals(text) ? bytes : null;
  }
}
