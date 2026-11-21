package me.sattle.checkout.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One holder at a time per key, in the order they asked (SPEC.md §17: within one client, calls for
 * the same {@code ref} run one after the other). A key nobody holds or waits for is forgotten.
 */
public final class KeyedLocks {
  private final Map<String, Entry> entries = new HashMap<>();

  /** A new set of locks. */
  public KeyedLocks() {}

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock(true);
    int users;
  }

  /** A lock held: {@link #close()} releases it. */
  public interface Held extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Takes the lock of {@code key}, waiting for the holders before.
   *
   * @param key the key
   * @return the lock, held
   * @throws InterruptedException when this thread is interrupted while it waits
   */
  public Held lock(String key) throws InterruptedException {
    Entry entry;
    synchronized (this) {
      entry = entries.computeIfAbsent(key, k -> new Entry());
      entry.users += 1;
    }
    try {
      entry.lock.lockInterruptibly();
    } catch (InterruptedException e) {
      leave(key, entry);
      throw e;
    }
    boolean[] released = {false};
    return () -> {
      if (released[0]) return;
      released[0] = true;
      entry.lock.unlock();
      leave(key, entry);
    };
  }

  private synchronized void leave(String key, Entry entry) {
    entry.users -= 1;
    if (entry.users == 0) entries.remove(key);
  }

  /**
   * How many keys are held or waited for (for tests: none are kept once released).
   *
   * @return the count
   */
  public synchronized int size() {
    return entries.size();
  }
}
