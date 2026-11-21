package me.sattle.checkout.internal;

/**
 * Where the package's warnings go (SPEC.md §4: warnings only, never a secret): the client's logger,
 * or the once-only warnings of one {@code waitForPayment}.
 */
@FunctionalInterface
public interface Warn {
  /** What each warning of the package begins with: its Maven coordinates. */
  String PREFIX = "me.sattle:checkout: ";

  /**
   * Logs a warning.
   *
   * @param message what happened, without a secret
   */
  void warn(String message);
}
