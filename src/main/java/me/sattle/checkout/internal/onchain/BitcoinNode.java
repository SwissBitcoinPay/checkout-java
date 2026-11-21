package me.sattle.checkout.internal.onchain;

import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonBoolean;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The merchant's Bitcoin node (SPEC.md §31, "The merchant's Bitcoin node"), valid: the channel's
 * {@code bitcoinNode} (SPEC.md §14), which the merchant's app writes, and the server then reads the
 * blockchain from, rather than from mempool.space.
 *
 * @param url a node URL ({@link NodeUrl}), as the channel writes it
 * @param fallback what a read does when the node does not answer it
 * @param payers whether the pages of checkout links read the node too (a node of the Internet only)
 */
public record BitcoinNode(String url, Fallback fallback, boolean payers) {
  /** What the server does when the node does not answer a read (SPEC.md §31, {@code fallback}). */
  public enum Fallback {
    /** The same read, of mempool.space. */
    MEMPOOL("mempool"),
    /** Never mempool.space: while the node does not answer, the read fails. */
    NONE("none");

    private final String json;

    Fallback(String json) {
      this.json = json;
    }

    /**
     * How a channel and a record write it.
     *
     * @return {@code "mempool"} or {@code "none"}
     */
    public String json() {
      return json;
    }

    /**
     * The fallback {@code value} is: exactly {@code "mempool"} or {@code "none"}.
     *
     * @param value a JSON value, or null when absent
     * @return the fallback, or null for anything else
     */
    public static Fallback of(JsonValue value) {
      String text = Json.string(value);
      if (MEMPOOL.json.equals(text)) return MEMPOOL;
      if (NONE.json.equals(text)) return NONE;
      return null;
    }
  }

  /**
   * The channel's {@code bitcoinNode} (SPEC.md §14, §31): an object, not an array, whose {@code
   * url} is a node URL, whose {@code fallback} is exactly {@code "mempool"} or {@code "none"} and
   * whose {@code payers} is a JSON boolean, {@code true} only for a node of the Internet; its other
   * members ignored. Nothing is repaired.
   *
   * @param value the field, or null when absent
   * @return the node, or null when it is absent or invalid in any way: there is none, the server
   *     reads mempool.space, and the channel stays valid
   */
  public static BitcoinNode of(JsonValue value) {
    JsonObject object = Json.object(value);
    if (object == null) return null;
    NodeUrl node = NodeUrl.parse(Json.string(object.get("url")));
    Fallback fallback = Fallback.of(object.get("fallback"));
    JsonValue payers = object.get("payers");
    if (node == null || fallback == null || !(payers instanceof JsonBoolean)) return null;
    boolean forPayers = payers == JsonBoolean.TRUE;
    // A link must never make each customer's browser probe its own local network.
    if (forPayers && !node.internet()) return null;
    return new BitcoinNode(node.url(), fallback, forPayers);
  }

  /**
   * What a checkout link's record of version 2 holds of {@code node} (SPEC.md §30): its {@code url}
   * and {@code fallback}, copied as they are, never {@code payers}.
   *
   * @param node the channel's node, or null
   * @return the record's node, or null (the field absent) when the channel names no node or its
   *     {@code payers} is false
   */
  public static RecordNode recordNodeFor(BitcoinNode node) {
    return node != null && node.payers() ? new RecordNode(node.url(), node.fallback()) : null;
  }

  /**
   * The node a checkout link's record names (SPEC.md §30), which its page reads the chain from.
   *
   * @param url a node URL of a node of the Internet
   * @param fallback what the page does when the node does not answer
   */
  public record RecordNode(String url, Fallback fallback) {
    /**
     * Its JSON in a record: {@code {"url": …, "fallback": …}}.
     *
     * @return the object
     */
    public JsonObject toJson() {
      return new JsonObject().put("url", url).put("fallback", fallback.json());
    }

    /**
     * The node a page reads from a record of version 2's {@code bitcoinNode} (SPEC.md §30, "Reading
     * a record"): an object whose {@code url} is a node URL of a node of the Internet and whose
     * {@code fallback} is exactly {@code "mempool"} or {@code "none"}, its other members ignored.
     *
     * @param value the field, or null when absent
     * @return the node, or null when it is absent or anything else ({@code null} included): set
     *     aside, the page reading mempool.space, the record valid
     */
    public static RecordNode of(JsonValue value) {
      JsonObject object = Json.object(value);
      if (object == null) return null;
      NodeUrl node = NodeUrl.parse(Json.string(object.get("url")));
      Fallback fallback = Fallback.of(object.get("fallback"));
      if (node == null || !node.internet() || fallback == null) return null;
      return new RecordNode(node.url(), fallback);
    }
  }
}
