package me.sattle.checkout.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

/**
 * Waits for what the relays past a majority take after a call returned: a call ends once a majority
 * took its writes, the others' go on (bounded by their timeout) on threads of their own.
 */
public final class Eventually {
  private Eventually() {}

  /**
   * Waits until {@code condition} holds, 5 s at most, then asserts it.
   *
   * @param condition what must come to hold
   * @throws InterruptedException when the test is interrupted
   */
  public static void until(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "never came to hold");
  }
}
