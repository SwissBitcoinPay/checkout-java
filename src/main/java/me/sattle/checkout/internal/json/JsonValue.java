package me.sattle.checkout.internal.json;

/**
 * A JSON value, as JavaScript's {@code JSON.parse} reads it (SPEC.md §2): an object (of members
 * with one name, the last counts), an array, a string (a lone surrogate escape kept as it is), a
 * number (an IEEE 754 double, whatever its spelling), a boolean (never a number) or null.
 */
public sealed interface JsonValue
    permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {}
