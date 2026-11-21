package me.sattle.checkout.internal.nostr;

import java.util.List;
import me.sattle.checkout.internal.json.EcmaNumbers;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;

/**
 * A Nostr event (NIP-01) whose shape was checked: hex32 id and pubkey, hex64 signature, a safe
 * integer {@code created_at}, an integer {@code kind}, tags of strings, well-formed texts.
 *
 * @param id the hex SHA-256 of its serialization
 * @param pubkey the hex x-only public key of its author
 * @param createdAt Unix seconds
 * @param kind its kind, an integer (a double: whatever a relay wrote)
 * @param tags its tags
 * @param content its content
 * @param sig the hex BIP-340 signature of its id
 */
public record NostrEvent(
    String id,
    String pubkey,
    long createdAt,
    double kind,
    List<List<String>> tags,
    String content,
    String sig) {

  /**
   * The event, its tags copied.
   *
   * @param id the hex SHA-256 of its serialization
   * @param pubkey the author
   * @param createdAt Unix seconds
   * @param kind its kind
   * @param tags its tags
   * @param content its content
   * @param sig its signature
   */
  public NostrEvent {
    tags = tags.stream().map(List::copyOf).toList();
  }

  /**
   * The first value of its first tag named {@code name}.
   *
   * @param name the tag's name
   * @return its value, or null
   */
  public String tag(String name) {
    for (List<String> tag : tags) {
      if (!tag.isEmpty() && tag.get(0).equals(name)) return tag.size() > 1 ? tag.get(1) : null;
    }
    return null;
  }

  /**
   * The event as JSON, as it is sent to a relay.
   *
   * @return its JSON object
   */
  public JsonObject toJson() {
    JsonArray tagArray = new JsonArray();
    for (List<String> tag : tags) tagArray.add(JsonArray.ofStrings(tag));
    return new JsonObject()
        .put("kind", new JsonNumber(kind))
        .put("created_at", createdAt)
        .put("tags", tagArray)
        .put("content", content)
        .put("id", id)
        .put("pubkey", pubkey)
        .put("sig", sig);
  }

  @Override
  public String toString() {
    return "NostrEvent[id="
        + id
        + ", pubkey="
        + pubkey
        + ", kind="
        + EcmaNumbers.format(kind)
        + "]";
  }
}
