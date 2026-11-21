package me.sattle.checkout.internal.nostr;

import me.sattle.checkout.internal.json.JsonValue;

/**
 * What a read keeps of an event a relay sent (SPEC.md §13), as it comes: what it opens to, or the
 * event itself, when it opens; null drops it. Its id and its date are kept anyway, to page the read
 * and count each event once: what a relay sends never piles up.
 */
@FunctionalInterface
public interface Keep {
  /**
   * What to keep of {@code event}.
   *
   * @param event what a relay sent
   * @return what to keep, or null
   */
  Object keep(JsonValue event);
}
