package me.sattle.checkout.support;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.crypto.Hashes;

/**
 * Random bytes from a counter, for reproducible tests only: each block of 32 bytes is the SHA-256
 * of the label and the counter's byte, as checkout-node's tests make them.
 */
public final class FixedRandom extends SecureRandom {
  private static final long serialVersionUID = 1L;

  private final byte[] label;
  private int counter;

  /**
   * Bytes of {@code label}.
   *
   * @param label the label
   */
  public FixedRandom(String label) {
    this.label = label.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void nextBytes(byte[] bytes) {
    for (int filled = 0; filled < bytes.length; filled += 32) {
      byte[] input = new byte[label.length + 1];
      System.arraycopy(label, 0, input, 0, label.length);
      input[label.length] = (byte) counter++;
      byte[] block = Hashes.sha256(input);
      System.arraycopy(block, 0, bytes, filled, Math.min(32, bytes.length - filled));
    }
  }

  /**
   * As the package draws them.
   *
   * @param label the label
   * @return the random bytes
   */
  public static RandomBytes of(String label) {
    return new RandomBytes(new FixedRandom(label));
  }

  /**
   * {@code length} bytes of {@code seed}: tests are reproducible.
   *
   * @param seed the byte
   * @param length how many
   * @return the bytes
   */
  public static byte[] bytes(int seed, int length) {
    byte[] bytes = new byte[length];
    java.util.Arrays.fill(bytes, (byte) seed);
    return bytes;
  }

  /**
   * 32 bytes of {@code seed}.
   *
   * @param seed the byte
   * @return the bytes
   */
  public static byte[] bytes(int seed) {
    return bytes(seed, 32);
  }
}
