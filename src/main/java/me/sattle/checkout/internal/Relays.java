package me.sattle.checkout.internal;

import java.util.ArrayList;
import java.util.List;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * Lists of relays, as the merchant's app writes them (SPEC.md §9): 1 to 10 addresses in their one
 * spelling, none twice. A list that is not so is refused, never repaired.
 */
public final class Relays {
  private Relays() {}

  /**
   * A valid list.
   *
   * @param value a JSON value, or null
   * @return the list, or null when it is not one
   */
  public static List<String> parseList(JsonValue value) {
    JsonArray array = Json.array(value);
    if (array == null || array.size() == 0 || array.size() > Constants.MAX_RELAYS) return null;
    List<String> list = new ArrayList<>();
    for (JsonValue item : array.items()) {
      String relay = Json.string(item);
      if (relay == null || !Urls.isRelayUrl(relay) || list.contains(relay)) return null;
      list.add(relay);
    }
    return List.copyOf(list);
  }

  /**
   * The list of a key's part (or a link's): hosts without {@code wss://}, joined by commas, in
   * strict base64url of strict UTF-8 (a byte order mark is a character: no host).
   *
   * @param text the part
   * @return the list, or null when it is not one
   */
  public static List<String> decodeList(String text) {
    byte[] bytes = Base64Strict.decodeUrl(text);
    if (bytes == null || bytes.length == 0) return null;
    String decoded = Json.strictUtf8(bytes);
    if (decoded == null) return null;
    JsonArray relays = new JsonArray();
    for (String host : decoded.split(",", -1)) relays.add("wss://" + host);
    return parseList(relays);
  }

  /**
   * A list as a key's part writes it: the hosts without {@code wss://}, joined by {@code ,}, in
   * base64url.
   *
   * @param relays the list
   * @return the part
   */
  public static String encodeList(List<String> relays) {
    List<String> hosts = new ArrayList<>();
    for (String relay : relays) hosts.add(relay.substring("wss://".length()));
    return Base64Strict.encodeUrl(Text.utf8(String.join(",", hosts)));
  }
}
