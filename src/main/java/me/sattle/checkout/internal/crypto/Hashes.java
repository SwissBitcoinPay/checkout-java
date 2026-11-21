package me.sattle.checkout.internal.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SHA-256, HMAC-SHA256 and HKDF-SHA256 (RFC 5869) on the platform's providers: the JDK's own KDF
 * API is of Java 25 alone, so HKDF is written here on {@link Mac}, as RFC 5869 defines it.
 */
public final class Hashes {
  private Hashes() {}

  /**
   * SHA-256 of {@code data}.
   *
   * @param data the bytes
   * @return their 32-byte digest
   */
  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }

  /**
   * SHA-256 of the UTF-8 of {@code text}.
   *
   * @param text a well-formed text
   * @return its 32-byte digest
   */
  public static byte[] sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * HMAC-SHA256 of {@code data} under {@code key}.
   *
   * @param key the key (any length)
   * @param data the bytes
   * @return the 32-byte MAC
   */
  public static byte[] hmacSha256(byte[] key, byte[]... data) {
    // Every key of the protocol has bytes (a salt, a derived key): an empty one is a bug here.
    if (key.length == 0) throw new IllegalArgumentException("Empty HMAC key");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (byte[] part : data) mac.update(part);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required of every Java platform", e);
    }
  }

  /**
   * HKDF-SHA256 (RFC 5869), extract then expand: 32 bytes of {@code secret} with {@code salt} and
   * {@code info} in UTF-8 (SPEC.md §2, §10).
   *
   * @param secret the input keying material
   * @param salt the salt (UTF-8)
   * @param info the info (UTF-8)
   * @return 32 bytes
   */
  public static byte[] hkdf(byte[] secret, String salt, String info) {
    byte[] prk = hkdfExtract(salt.getBytes(StandardCharsets.UTF_8), secret);
    return hkdfExpand(prk, info.getBytes(StandardCharsets.UTF_8), 32);
  }

  /**
   * HKDF-Extract (RFC 5869 §2.2): HMAC(salt, input keying material).
   *
   * @param salt the salt
   * @param ikm the input keying material
   * @return the 32-byte pseudorandom key
   */
  public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
    return hmacSha256(salt, ikm);
  }

  /**
   * HKDF-Expand (RFC 5869 §2.3).
   *
   * @param prk the pseudorandom key
   * @param info the info
   * @param length the bytes wanted, at most 255 × 32
   * @return {@code length} bytes
   */
  public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
    if (length < 0 || length > 255 * 32) throw new IllegalArgumentException("HKDF length");
    byte[] out = new byte[length];
    byte[] previous = new byte[0];
    int filled = 0;
    for (int block = 1; filled < length; block += 1) {
      previous = hmacSha256(prk, previous, info, new byte[] {(byte) block});
      int take = Math.min(previous.length, length - filled);
      System.arraycopy(previous, 0, out, filled, take);
      filled += take;
    }
    return out;
  }

  /**
   * Whether {@code a} and {@code b} are equal, compared in constant time (a MAC).
   *
   * @param a bytes
   * @param b bytes
   * @return true when equal
   */
  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
