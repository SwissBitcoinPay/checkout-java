package me.sattle.checkout.internal.json;

/**
 * A JSON string: its UTF-16 text as JavaScript holds it, a lone surrogate included.
 *
 * @param value the text
 */
public record JsonString(String value) implements JsonValue {}
