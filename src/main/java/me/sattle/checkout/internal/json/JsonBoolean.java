package me.sattle.checkout.internal.json;

/** A JSON boolean: never a number. */
public enum JsonBoolean implements JsonValue {
  /** {@code true}. */
  TRUE,
  /** {@code false}. */
  FALSE;

  /**
   * The boolean of {@code value}.
   *
   * @param value a boolean
   * @return its JSON value
   */
  public static JsonBoolean of(boolean value) {
    return value ? TRUE : FALSE;
  }
}
