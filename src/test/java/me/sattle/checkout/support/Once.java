package me.sattle.checkout.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An action a relay's {@code onEvent} runs once, the first time, while every other caller waits
 * until it ran: each relay holds back its OK meanwhile, as one event loop would (checkout-node's
 * relays and package share one thread, so what a relay does on an event is done before the package
 * hears of it). Without it, the package could read a relay before another relay's action reached
 * it.
 */
public final class Once {
  private final AtomicBoolean started = new AtomicBoolean();
  private final CountDownLatch done = new CountDownLatch(1);

  /** An action not run yet. */
  public Once() {}

  /**
   * Runs {@code action} if it is the first call; else waits until the first call ran it.
   *
   * @param action what to run once
   */
  public void run(Runnable action) {
    if (started.compareAndSet(false, true)) {
      try {
        action.run();
      } finally {
        done.countDown();
      }
      return;
    }
    try {
      done.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Whether it ran (or is running).
   *
   * @return true once started
   */
  public boolean ran() {
    return started.get();
  }
}
