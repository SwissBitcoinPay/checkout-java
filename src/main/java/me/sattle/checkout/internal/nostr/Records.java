package me.sattle.checkout.internal.nostr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * Sealed records (SPEC.md §12), as the merchant's app writes and reads them: one addressable event
 * of kind 30078 (NIP-78), an opaque {@code d} tag, NIP-44 v2 content whose plaintext {@code {"t":
 * <type>, "v": <value>}} is padded with spaces to a multiple of 256 bytes.
 */
public final class Records {
  private Records() {}

  /** A record of a newer format than this package reads: never used, never written over. */
  public static final class NewerFormat extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** A newer format. */
    public NewerFormat() {
      super("Record of a newer format", null, false, false);
    }
  }

  /**
   * How a record's value is validated (SPEC.md §12, step 5).
   *
   * @param <T> what it opens to
   */
  @FunctionalInterface
  public interface Parse<T> {
    /**
     * The value, validated.
     *
     * @param value the record's value
     * @param event its event, checked
     * @return what it opens to, or null when it is not valid
     * @throws NewerFormat for a newer version
     */
    T parse(JsonValue value, NostrEvent event);
  }

  /**
   * A record opened: its value ({@code value} non-null), or authentic but of a newer format.
   *
   * @param event the event, checked
   * @param value what it opens to; null when it is of a newer format
   * @param <T> what it opens to
   */
  public record Opened<T>(NostrEvent event, T value) {
    /**
     * Whether it is of a newer format.
     *
     * @return true for a newer format
     */
    public boolean isNewer() {
      return value == null;
    }
  }

  /**
   * The signed event of a record (SPEC.md §12, "Sealing"): {@code {"t": type, "v": value}}, spaces
   * up to the next multiple of 256 bytes, NIP-44 v2 under {@code encryptionKey} with a fresh nonce,
   * then signed by {@code signer}.
   *
   * @param type the record's type
   * @param value its value
   * @param signer the author's secret key
   * @param encryptionKey the NIP-44 conversation key
   * @param address the {@code d} tag
   * @param createdAt Unix seconds
   * @param tags the tags after the {@code d} tag
   * @param random where the nonce and the auxiliary data come from
   * @return the signed event
   * @throws Nip44.PlaintextException when the record does not fit a NIP-44 plaintext
   */
  public static NostrEvent seal(
      String type,
      JsonValue value,
      byte[] signer,
      byte[] encryptionKey,
      String address,
      long createdAt,
      List<List<String>> tags,
      RandomBytes random) {
    String text = Json.write(new JsonObject().put("t", type).put("v", value));
    int length = Text.utf8Length(text);
    int blocks = (length + Constants.RECORD_BLOCK_BYTES - 1) / Constants.RECORD_BLOCK_BYTES;
    // Spaces after the JSON: whitespace where it is parsed.
    String padded = text + " ".repeat(blocks * Constants.RECORD_BLOCK_BYTES - length);
    String content = Nip44.encrypt(padded, encryptionKey, random.next(32));
    List<List<String>> all = new ArrayList<>();
    all.add(List.of("d", address));
    all.addAll(tags);
    return Events.sign(Constants.RECORD_KIND, createdAt, all, content, signer, random);
  }

  /**
   * What a relay sent, opened as a record of {@code type} at {@code address} by {@code author}
   * (SPEC.md §12, "Opening"), the cheap checks first: its kind, author, content and {@code d} tag;
   * its content decrypts (of a newer NIP-44 version: newer once the event checks out); the
   * plaintext a JSON object holding {@code v} of the type; the event checks out; the value valid.
   * Anything else is null: a relay is never trusted.
   *
   * @param value what a relay sent
   * @param type the record's type
   * @param encryptionKey its NIP-44 conversation key
   * @param address the {@code d} tag it must have
   * @param author the author it must have (hex)
   * @param parse the validation of its value
   * @param <T> what it opens to
   * @return the record opened, or null
   */
  public static <T> Opened<T> open(
      JsonValue value,
      String type,
      byte[] encryptionKey,
      String address,
      String author,
      Parse<T> parse) {
    JsonObject claimed = Json.object(value);
    if (claimed == null
        || !Json.isNumber(claimed.get("kind"), Constants.RECORD_KIND)
        || !author.equals(Json.string(claimed.get("pubkey")))
        || Json.string(claimed.get("content")) == null
        || !hasTag(claimed, "d", address)) {
      return null;
    }
    JsonValue payload;
    boolean newer = false;
    try {
      payload = readContent(Json.string(claimed.get("content")), type, encryptionKey);
    } catch (NewerFormat e) {
      payload = null;
      newer = true;
    }
    if (!newer && payload == null) return null;
    NostrEvent event = Events.checked(value);
    if (event == null) return null;
    if (newer) return new Opened<>(event, null);
    try {
      T parsed = parse.parse(payload, event);
      return parsed == null ? null : new Opened<>(event, parsed);
    } catch (NewerFormat e) {
      return new Opened<>(event, null);
    }
  }

  /** Whether an event a relay sent holds the tag {@code [name, value, …]}. */
  private static boolean hasTag(JsonObject event, String name, String value) {
    JsonArray tags = Json.array(event.get("tags"));
    if (tags == null) return false;
    for (JsonValue tagValue : tags.items()) {
      JsonArray tag = Json.array(tagValue);
      if (tag != null
          && tag.size() >= 2
          && name.equals(Json.string(tag.get(0)))
          && value.equals(Json.string(tag.get(1)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The value inside a record's content: null when it does not open or is of another type.
   *
   * @throws NewerFormat for a content of a newer NIP-44 version
   */
  private static JsonValue readContent(String content, String type, byte[] encryptionKey) {
    String text;
    try {
      text = Nip44.decrypt(content, encryptionKey);
    } catch (Nip44.UnknownVersionException e) {
      throw new NewerFormat();
    } catch (Nip44.DecryptException e) {
      return null;
    }
    JsonObject envelope = Json.object(Json.parseOrNull(text));
    if (envelope == null || !envelope.has("v")) return null;
    return type.equals(Json.string(envelope.get("t"))) ? envelope.get("v") : null;
  }

  /** The conversation key a read opens an event with, by its {@code d} tag and author. */
  @FunctionalInterface
  public interface KeyOf {
    /**
     * The key, or null for an author or an address the read does not open.
     *
     * @param d the event's first {@code d} tag when it is hex32, else null
     * @param author the event's author
     * @return the NIP-44 conversation key, or null
     */
    byte[] keyOf(String d, String author);
  }

  /**
   * What a read of sealed records keeps of each event (SPEC.md §13: the event, when it opens): an
   * event of kind 30078 whose content decrypts with {@code keyOf} its {@code d} tag and author (its
   * MAC checked), or is of a newer NIP-44 version, and that checks out (SPEC.md §11), its signature
   * included: anybody may send a copy of a record under an id of its own, its content and signature
   * left as they are, and only the signature tells it from the record. Checked once per id and
   * signature. Its value is not checked here: the read opens what it keeps. Of the event, only its
   * NIP-01 members are kept: a relay may add any other to a record that checks out, which its id
   * and signature cover none of.
   *
   * @param keyOf the keys of the read
   * @return what keeps an event (its NIP-01 members, as sent), or drops it (null)
   */
  public static Keep recordsThatOpen(KeyOf keyOf) {
    Map<String, Boolean> verified = new HashMap<>();
    return value -> {
      JsonObject event = Json.object(value);
      if (event == null
          || !Json.isNumber(event.get("kind"), Constants.RECORD_KIND)
          || Json.string(event.get("content")) == null
          || Json.string(event.get("pubkey")) == null) {
        return null;
      }
      byte[] key = keyOf.keyOf(dTagOf(value), Json.string(event.get("pubkey")));
      if (key == null) return null;
      try {
        Nip44.decrypt(Json.string(event.get("content")), key);
      } catch (Nip44.UnknownVersionException e) {
        // A newer format: kept when it checks out.
      } catch (Nip44.DecryptException e) {
        return null;
      }
      NostrEvent checked = Events.withValidId(value);
      if (checked == null) return null;
      String copy = checked.id() + "/" + checked.sig();
      Boolean valid = verified.get(copy);
      if (valid == null) {
        valid = Events.signatureChecks(checked.id(), checked.pubkey(), checked.sig());
        verified.put(copy, valid);
      }
      return valid ? Events.nip01Members(value) : null;
    };
  }

  /**
   * The {@code d} tag of what a relay sent, when it is hex32 (its first {@code d} tag).
   *
   * @param value what a relay sent
   * @return the tag's value, or null
   */
  public static String dTagOf(JsonValue value) {
    JsonObject event = Json.object(value);
    JsonArray tags = event == null ? null : Json.array(event.get("tags"));
    if (tags == null) return null;
    for (JsonValue tagValue : tags.items()) {
      JsonArray tag = Json.array(tagValue);
      if (tag != null && tag.size() >= 1 && "d".equals(Json.string(tag.get(0)))) {
        String d = tag.size() >= 2 ? Json.string(tag.get(1)) : null;
        return Text.isHex32(d) ? d : null;
      }
    }
    return null;
  }

  /**
   * Whether the event {@code a} replaces {@code b} as relays keep them: a later {@code created_at},
   * then the lowest id (NIP-01).
   *
   * @param aCreatedAt a's date
   * @param aId a's id
   * @param bCreatedAt b's date
   * @param bId b's id
   * @return true when a replaces b
   */
  public static boolean replaces(long aCreatedAt, String aId, long bCreatedAt, String bId) {
    return aCreatedAt > bCreatedAt || (aCreatedAt == bCreatedAt && aId.compareTo(bId) < 0);
  }

  /**
   * The newest of the records relays sent.
   *
   * @param newest the newest valid copy, or null
   * @param newestUnreadable the {@code created_at} of the newest copy of a newer format, or null
   * @param <T> what they open to
   */
  public record Newest<T>(Opened<T> newest, Long newestUnreadable) {}

  /**
   * The events a read kept, as they came ({@link Keep} kept each event's NIP-01 members).
   *
   * @param answers each relay's kept events
   * @return each relay's events, as JSON values
   */
  public static Map<String, List<JsonValue>> asValues(Map<String, List<Object>> answers) {
    Map<String, List<JsonValue>> values = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<Object>> relay : answers.entrySet()) {
      List<JsonValue> events = new ArrayList<>();
      for (Object event : relay.getValue()) events.add((JsonValue) event);
      values.put(relay.getKey(), events);
    }
    return values;
  }

  /**
   * Reads the answers of a query: every copy opened, once per event id that checked out (another
   * copy with that id is the same event); the newest by {@code created_at} wins, on a tie the
   * lowest id (the one relays keep).
   *
   * @param answers each relay's events
   * @param open how a copy opens
   * @param <T> what they open to
   * @return the newest copy, and the date of the newest of a newer format
   */
  public static <T> Newest<T> newest(
      Map<String, List<JsonValue>> answers,
      java.util.function.Function<JsonValue, Opened<T>> open) {
    Set<String> checked = new HashSet<>();
    Opened<T> newest = null;
    Long newestUnreadable = null;
    for (List<JsonValue> events : answers.values()) {
      for (JsonValue value : events) {
        JsonObject object = Json.object(value);
        String id = object == null ? null : Json.string(object.get("id"));
        if (id != null && checked.contains(id)) continue;
        Opened<T> copy = open.apply(value);
        if (copy == null) continue;
        checked.add(copy.event().id());
        long createdAt = copy.event().createdAt();
        if (copy.isNewer()) {
          newestUnreadable =
              newestUnreadable == null ? createdAt : Math.max(newestUnreadable, createdAt);
          continue;
        }
        if (newest == null
            || replaces(
                createdAt, copy.event().id(), newest.event().createdAt(), newest.event().id())) {
          newest = copy;
        }
      }
    }
    return new Newest<>(newest, newestUnreadable);
  }
}
