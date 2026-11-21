package me.sattle.checkout.internal.nostr;

import java.util.Set;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * What a read keeps of an event, as it comes: its id (one longer than 64 characters as {@code #}
 * and its SHA-256: the same id, the same hash), its date, and what {@link Keep} kept of it.
 *
 * @param id its id, or null when it is not a string
 * @param createdAt its {@code created_at}, or null when it is not a safe integer
 * @param value what was kept of it, or null
 */
public record Entry(String id, Long createdAt, Object value) {
  /**
   * The entry of {@code value}.
   *
   * @param value what a relay sent
   * @param keep what the read keeps
   * @return its entry
   */
  public static Entry of(JsonValue value, Keep keep) {
    return of(value, keep, null);
  }

  /**
   * The entry of {@code value}, an event of one relay's answer to a query (SPEC.md §13, "Limits"):
   * {@code read} holds the ids of that answer so far. The first event of each id is the one read; a
   * later one of that id (a copy, or a forgery: an id is its event's hash) only counts, its id and
   * date kept, nothing of it kept, opened or checked.
   *
   * @param value what a relay sent
   * @param keep what the read keeps
   * @param read the ids of the query's answer so far (this one's added), or null
   * @return its entry
   */
  public static Entry of(JsonValue value, Keep keep, Set<String> read) {
    JsonObject event = Json.object(value);
    String id = event == null ? null : Json.string(event.get("id"));
    if (id != null && id.length() > 64) id = "#" + Hex.encode(Hashes.sha256(Text.utf8(id)));
    Long createdAt = event == null ? null : Json.safeInteger(event.get("created_at"));
    if (id != null && read != null && !read.add(id)) return new Entry(id, createdAt, null);
    return new Entry(id, createdAt, keep.keep(value));
  }
}
