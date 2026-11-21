package me.sattle.checkout.internal;

import java.util.HexFormat;

/** Lowercase hex, two characters per byte (SPEC.md §2). */
public final class Hex {
  private Hex() {}

  private static final HexFormat FORMAT = HexFormat.of();

  /**
   * The lowercase hex of {@code bytes}.
   *
   * @param bytes the bytes
   * @return their hex
   */
  public static String encode(byte[] bytes) {
    return FORMAT.formatHex(bytes);
  }

  /**
   * The bytes of hex already checked ({@link Text#isLowerHex}).
   *
   * @param hex an even number of hex digits
   * @return the bytes
   */
  public static byte[] decode(String hex) {
    return FORMAT.parseHex(hex);
  }
}
