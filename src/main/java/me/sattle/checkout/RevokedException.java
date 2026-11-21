package me.sattle.checkout;

/** The merchant removed the integration: no invoice can be made with this key any more. */
public final class RevokedException extends SattlemeException {
  private static final long serialVersionUID = 1L;

  /** The integration was removed. */
  public RevokedException() {
    super("revoked", "The merchant removed this integration", false, null);
  }
}
