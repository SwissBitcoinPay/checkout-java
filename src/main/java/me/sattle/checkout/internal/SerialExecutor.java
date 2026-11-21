package me.sattle.checkout.internal;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * An event loop: the tasks given to it run one after the other, in their order, on the threads of
 * {@link Tasks#WORKERS}. The relays of a call live on one (as node's event loop runs them): their
 * state is touched by its tasks alone, and none of them blocks.
 */
public final class SerialExecutor implements Executor {
  private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
  private boolean running;

  /** A new event loop. */
  public SerialExecutor() {}

  @Override
  public void execute(Runnable task) {
    synchronized (this) {
      tasks.add(task);
      if (running) return;
      running = true;
    }
    Tasks.WORKERS.execute(this::drain);
  }

  private void drain() {
    for (; ; ) {
      Runnable task;
      synchronized (this) {
        task = tasks.poll();
        if (task == null) {
          running = false;
          return;
        }
      }
      try {
        task.run();
      } catch (RuntimeException | Error e) {
        // A bug: reported as an uncaught exception, the loop going on with its next task.
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
      }
    }
  }
}
