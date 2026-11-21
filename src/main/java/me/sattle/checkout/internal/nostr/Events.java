package me.sattle.checkout.internal.nostr;

import java.util.ArrayList;
import java.util.List;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * Nostr events (SPEC.md §11): the id, SHA-256 of the UTF-8 of {@code [0, pubkey, created_at, kind,
 * tags, content]} as {@code JSON.stringify} writes it (numbers as integers whatever their spelling
 * in what a relay sent); BIP-340 signatures with 32 fresh random bytes of auxiliary data; the
 * checks an event from a relay passes before it is used. A relay is never trusted.
 */
public final class Events {
  private Events() {}

  /**
   * The id of an event: the hex SHA-256 of its NIP-01 serialization.
   *
   * @param pubkey its author
   * @param createdAt its date
   * @param kind its kind
   * @param tags its tags
   * @param content its content (well-formed)
   * @return the hex id
   */
  public static String id(
      String pubkey, long createdAt, double kind, List<List<String>> tags, String content) {
    return Hex.encode(Hashes.sha256(serialized(pubkey, createdAt, kind, tags, content)));
  }

  /**
   * An event's NIP-01 serialization, whose SHA-256 is its id: {@code [0, pubkey, created_at, kind,
   * tags, content]}, no whitespace, its numbers as integers, its strings as {@code JSON.stringify}
   * writes them (SPEC.md §11).
   *
   * @param pubkey its author
   * @param createdAt its date
   * @param kind its kind
   * @param tags its tags
   * @param content its content (well-formed)
   * @return the serialization
   */
  public static String serialized(
      String pubkey, long createdAt, double kind, List<List<String>> tags, String content) {
    JsonArray tagArray = new JsonArray();
    for (List<String> tag : tags) tagArray.add(JsonArray.ofStrings(tag));
    return Json.write(
        new JsonArray()
            .add(0)
            .add(pubkey)
            .add(new JsonNumber(createdAt))
            .add(new JsonNumber(kind))
            .add(tagArray)
            .add(content));
  }

  /**
   * The members an id covers of what a relay sent, when they are of an event's types: {@code
   * pubkey} hex32, {@code created_at} a safe integer, {@code kind} an integer, {@code tags} arrays
   * of strings, {@code content} a string, its texts well-formed (a lone surrogate has no UTF-8,
   * which the hash needs).
   *
   * @param pubkey its author
   * @param createdAt its date
   * @param kind its kind
   * @param tags its tags
   * @param content its content
   */
  public record Covered(
      String pubkey, long createdAt, double kind, List<List<String>> tags, String content) {
    /**
     * Its serialization ({@link #serialized}).
     *
     * @return the serialization
     */
    public String serialized() {
      return Events.serialized(pubkey, createdAt, kind, tags, content);
    }

    /**
     * Its id ({@link #id}).
     *
     * @return the hex id
     */
    public String id() {
      return Events.id(pubkey, createdAt, kind, tags, content);
    }
  }

  /**
   * What an id covers of {@code event}, read as an event's check reads it (SPEC.md §11).
   *
   * @param event what a relay sent
   * @return its covered members, or null when one is not of an event's type
   */
  public static Covered covered(JsonObject event) {
    String pubkey = Json.string(event.get("pubkey"));
    Long createdAt = Json.safeInteger(event.get("created_at"));
    JsonValue kind = event.get("kind");
    String content = Json.string(event.get("content"));
    JsonArray tagArray = Json.array(event.get("tags"));
    if (!Text.isHex32(pubkey)
        || createdAt == null
        || !Json.isInteger(kind)
        || content == null
        || !Text.isWellFormed(content)
        || tagArray == null) {
      return null;
    }
    List<List<String>> tags = new ArrayList<>(tagArray.size());
    for (JsonValue tagValue : tagArray.items()) {
      JsonArray tag = Json.array(tagValue);
      if (tag == null) return null;
      List<String> items = new ArrayList<>(tag.size());
      for (JsonValue item : tag.items()) {
        if (!(item instanceof JsonString) || !Text.isWellFormed(((JsonString) item).value())) {
          return null;
        }
        items.add(((JsonString) item).value());
      }
      tags.add(items);
    }
    return new Covered(pubkey, createdAt, Json.number(kind), tags, content);
  }

  /**
   * An event signed by {@code secret} (BIP-340, 32 fresh random bytes of auxiliary data).
   *
   * @param kind its kind
   * @param createdAt Unix seconds
   * @param tags its tags
   * @param content its content
   * @param secret the author's secret key
   * @param random where the auxiliary data comes from
   * @return the signed event
   */
  public static NostrEvent sign(
      int kind,
      long createdAt,
      List<List<String>> tags,
      String content,
      byte[] secret,
      RandomBytes random) {
    String pubkey = Curve.publicKeyOf(secret);
    String id = id(pubkey, createdAt, kind, tags, content);
    byte[] sig = Curve.sign(Hex.decode(id), secret, random.next(32));
    return new NostrEvent(id, pubkey, createdAt, kind, tags, content, Hex.encode(sig));
  }

  /**
   * {@code value} as an event when it has the shape of one and its id is its hash (SPEC.md §11),
   * its signature not checked yet: what is checked of a record before anything else where anybody
   * may write (a lease), its signature only where it decides. A string of its content or tags
   * holding a lone surrogate has no UTF-8, which its hash needs: such an event does not check out.
   *
   * @param value what a relay sent
   * @return the event, or null
   */
  public static NostrEvent withValidId(JsonValue value) {
    JsonObject event = Json.object(value);
    if (event == null) return null;
    String id = Json.string(event.get("id"));
    String sig = Json.string(event.get("sig"));
    if (!Text.isHex32(id) || !Text.isHex64(sig)) return null;
    Covered covered = covered(event);
    if (covered == null || !covered.id().equals(id)) return null;
    return new NostrEvent(
        id,
        covered.pubkey(),
        covered.createdAt(),
        covered.kind(),
        covered.tags(),
        covered.content(),
        sig);
  }

  /** The members of NIP-01's events: what an id and a signature cover, and themselves. */
  private static final List<String> NIP01_MEMBERS =
      List.of("id", "pubkey", "created_at", "kind", "tags", "content", "sig");

  /**
   * The NIP-01 members of what a relay sent, an event that checked out, as it sent them, and
   * nothing else: what a relay adds to an event (its id and signature cover none of it) is never
   * kept (SPEC.md §13, "Limits").
   *
   * @param value an event that checked out
   * @return its {@code id}, {@code pubkey}, {@code created_at}, {@code kind}, {@code tags}, {@code
   *     content} and {@code sig}
   */
  public static JsonObject nip01Members(JsonValue value) {
    JsonObject event = Json.object(value);
    JsonObject members = new JsonObject();
    for (String name : NIP01_MEMBERS) {
      JsonValue member = event.get(name);
      if (member != null) members.put(name, member);
    }
    return members;
  }

  /**
   * {@code value} as an event that checks out: its shape, its id and its signature (SPEC.md §11).
   *
   * @param value what a relay sent
   * @return the event, or null
   */
  public static NostrEvent checked(JsonValue value) {
    NostrEvent event = withValidId(value);
    return event != null && signatureChecks(event.id(), event.pubkey(), event.sig()) ? event : null;
  }

  /**
   * Whether {@code sig} is a BIP-340 signature of the id {@code id} by {@code pubkey} (all hex,
   * checked).
   *
   * @param id the event's id
   * @param pubkey its author
   * @param sig the signature
   * @return true when it verifies
   */
  public static boolean signatureChecks(String id, String pubkey, String sig) {
    return Curve.verify(Hex.decode(sig), Hex.decode(id), Hex.decode(pubkey));
  }
}
