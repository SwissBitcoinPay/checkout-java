package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;

/**
 * The integration's lot (SPEC.md §31, "The lot"): deposit addresses of the merchant's wallet, which
 * the merchant's app gives the integration and fills again whenever the merchant opens it. One
 * record signed by the team, sealed with the channel's key, at an address the integration key
 * derives. The package only reads it: it never makes an address, and never writes the lot.
 */
public final class Lot {
  private Lot() {}

  /**
   * A lot's value: version 1, {@code updatedAt} its event's {@code created_at}, 0 to 100 deposit
   * addresses, none twice.
   *
   * @param value the record's value
   * @param createdAt its event's date
   * @return the addresses, or null when it is not valid
   * @throws Records.NewerFormat for a newer version
   */
  public static List<String> parseLot(JsonValue value, long createdAt) {
    JsonObject v = Json.object(value);
    if (v == null) return null;
    if (Json.isNumberAbove(v.get("version"), Constants.LOT_VERSION))
      throw new Records.NewerFormat();
    if (!Json.isNumber(v.get("version"), Constants.LOT_VERSION)
        || !Json.isNumber(v.get("updatedAt"), createdAt)) {
      return null;
    }
    JsonArray addresses = Json.array(v.get("addresses"));
    if (addresses == null || addresses.size() > Constants.MAX_LOT_ADDRESSES) return null;
    List<String> lot = new ArrayList<>();
    for (JsonValue item : addresses.items()) {
      String address = Json.string(item);
      if (!Addresses.isDepositAddress(address) || lot.contains(address)) return null;
      lot.add(address);
    }
    return List.copyOf(lot);
  }

  /**
   * The addresses of the integration's lot, read on the merchant's relays as soon as a majority
   * answered: the newest valid copy. Empty when there is none, or when a copy of a newer format is
   * as new or newer (logged: update the package).
   *
   * @param pool the call's relays
   * @param access the integration key
   * @param relays the merchant's relays
   * @param random the CSPRNG
   * @param logger where warnings go
   * @return the lot
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority answered
   */
  public static List<String> readLot(
      RelayPool pool, IntegrationKey access, List<String> relays, RandomBytes random, Warn logger)
      throws InterruptedException {
    byte[] key = access.key();
    String address = Derive.lotAddress(key);
    byte[] encryptionKey = Derive.channelEncryptionKey(key);
    String team = access.teamPublicKey();
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(team)))
            .put("#d", JsonArray.ofStrings(List.of(address)));
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            random.next(32),
            false,
            Records.recordsThatOpen((d, author) -> author.equals(team) ? encryptionKey : null),
            false);
    if (!read.outcome().answered()) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    Map<String, List<Object>> answers = read.events();
    Records.Newest<List<String>> found =
        Records.newest(
            Records.asValues(answers),
            value ->
                Records.open(
                    value,
                    Constants.LOT_RECORD_TYPE,
                    encryptionKey,
                    address,
                    team,
                    (v, event) -> parseLot(v, event.createdAt())));
    Long newer = found.newestUnreadable();
    if (newer != null
        && newer >= (found.newest() == null ? -1 : found.newest().event().createdAt())) {
      logger.warn(
          Warn.PREFIX
              + "the integration's lot of addresses has a newer format than this version reads;"
              + " update the package (no on-chain payment until then)");
      return List.of();
    }
    return found.newest() == null ? List.of() : found.newest().value();
  }
}
