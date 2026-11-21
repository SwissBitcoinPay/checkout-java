package me.sattle.checkout.internal.lightning;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Bech32 decoding (BIP-173), without its 90-character limit (BOLT11 invoices are longer), and
 * bech32m (BIP-350, taproot addresses), which differs by its checksum's constant alone. Either all
 * lowercase or all uppercase, ASCII only: never Unicode case mapping (SPEC.md §6).
 */
public final class Bech32 {
  private Bech32() {}

  private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
  private static final int[] GENERATOR = {
    0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
  };

  /** The checksum's constant of BIP-173's bech32. */
  public static final int BECH32 = 1;

  /** The checksum's constant of BIP-350's bech32m. */
  public static final int BECH32M = 0x2bc830a3;

  /**
   * A decoded bech32 string.
   *
   * @param hrp its human-readable part, lowercase
   * @param words its 5-bit words, the checksum removed
   */
  public record Decoded(String hrp, int[] words) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Decoded
          && hrp.equals(((Decoded) other).hrp)
          && Arrays.equals(words, ((Decoded) other).words);
    }

    @Override
    public int hashCode() {
      return 31 * hrp.hashCode() + Arrays.hashCode(words);
    }

    @Override
    public String toString() {
      return "Decoded[hrp=" + hrp + ", words=" + words.length + "]";
    }
  }

  private static int polymod(int[] values) {
    int chk = 1;
    for (int value : values) {
      int top = chk >>> 25;
      chk = ((chk & 0x1ffffff) << 5) ^ value;
      for (int i = 0; i < 5; i += 1) if (((top >>> i) & 1) != 0) chk ^= GENERATOR[i];
    }
    return chk;
  }

  /**
   * Decodes a bech32 string (or bech32m, with {@code constant} {@link #BECH32M}).
   *
   * @param input the text
   * @param constant the checksum's constant
   * @return its parts, or null when it is not valid
   */
  public static Decoded decode(String input, int constant) {
    boolean lower = false;
    boolean upper = false;
    for (int i = 0; i < input.length(); i += 1) {
      char c = input.charAt(i);
      if (c >= 'a' && c <= 'z') lower = true;
      if (c >= 'A' && c <= 'Z') upper = true;
      // Only printable ASCII: no Unicode case nor character ever reads as one of the charset.
      if (c < 33 || c > 126) return null;
    }
    if (lower && upper) return null;
    String text = lowerAscii(input);
    int separator = text.lastIndexOf('1');
    if (separator < 1 || separator + 7 > text.length()) return null;
    String hrp = text.substring(0, separator);
    int dataLength = text.length() - separator - 1;
    int[] values = new int[hrp.length() * 2 + 1 + dataLength];
    for (int i = 0; i < hrp.length(); i += 1) {
      int code = hrp.charAt(i);
      values[i] = code >> 5;
      values[hrp.length() + 1 + i] = code & 31;
    }
    values[hrp.length()] = 0;
    int[] data = new int[dataLength];
    for (int i = 0; i < dataLength; i += 1) {
      int value = CHARSET.indexOf(text.charAt(separator + 1 + i));
      if (value < 0) return null;
      data[i] = value;
      values[hrp.length() * 2 + 1 + i] = value;
    }
    if (polymod(values) != constant) return null;
    return new Decoded(hrp, Arrays.copyOf(data, dataLength - 6));
  }

  private static String lowerAscii(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
    }
    return out.toString();
  }

  /**
   * Regroups 5-bit words into bytes; the trailing padding bits (fewer than 8) are dropped.
   *
   * @param words 5-bit words
   * @return the bytes
   */
  public static byte[] wordsToBytes(int[] words) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int accumulator = 0;
    int bits = 0;
    for (int word : words) {
      accumulator = ((accumulator << 5) | word) & 0xfff;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out.write((accumulator >> bits) & 0xff);
      }
    }
    return out.toByteArray();
  }

  /**
   * The big-endian unsigned integer of 5-bit words (at most 12 of them: 60 bits).
   *
   * @param words 5-bit words
   * @return their value
   */
  public static long wordsToInteger(int[] words) {
    long value = 0;
    for (int word : words) value = value * 32 + word;
    return value;
  }
}
