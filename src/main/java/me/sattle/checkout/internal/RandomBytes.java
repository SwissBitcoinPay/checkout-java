package me.sattle.checkout.internal;

import java.security.SecureRandom;

/**
 * Where random bytes come from (SPEC.md §27): the NIP-44 nonces, the BIP-340 auxiliary data, the
 * keys of a single relay read, the subscription ids, the keys of a checkout link, the shuffle of
 * free addresses. The platform's CSPRNG ({@link SecureRandom}), replaceable for tests only.
 */
public final class RandomBytes {
  private final SecureRandom random;

  /**
   * Random bytes of {@code random}.
   *
   * @param random a CSPRNG
   */
  public RandomBytes(SecureRandom random) {
    this.random = random;
  }

  /**
   * Zero bytes, never random: for an event made for its size alone, never published (the size of a
   * lease before its address is known), so that the client's own random bytes are left alone.
   *
   * @return bytes that are all zero
   */
  public static RandomBytes zeros() {
    return new RandomBytes(null);
  }

  /**
   * {@code length} random bytes.
   *
   * @param length how many
   * @return fresh bytes (zero bytes of {@link #zeros()})
   */
  public byte[] next(int length) {
    byte[] bytes = new byte[length];
    if (random != null) random.nextBytes(bytes);
    return bytes;
  }

  @Override
  public String toString() {
    return "RandomBytes";
  }
}
