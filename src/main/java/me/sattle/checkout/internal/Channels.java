package me.sattle.checkout.internal;

import java.util.List;
import java.util.Map;
import me.sattle.checkout.ChannelNotFoundException;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.RevokedException;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.BitcoinNode;
import me.sattle.checkout.internal.onchain.Tolerance;

/**
 * The integration's channel (SPEC.md §14): one record signed by the team, which the merchant's app
 * publishes on the account's relays and which only the integration's key opens. It gives the
 * merchant's Lightning address, registry and relays, or says that the integration was removed.
 */
public final class Channels {
  private Channels() {}

  /**
   * The channel, as far as the server uses it.
   *
   * @param removed whether the integration was removed (the other fields are then null or 0)
   * @param businessName the business's name
   * @param lightningAddress the merchant's Lightning address: never given to the integrator
   * @param registryKey the registry key (hex)
   * @param registryReader the public half of the merchant's reading key (hex)
   * @param relays the account's relays when the channel names them, else null
   * @param checkoutOrigin the merchant's web app for checkout links, null when it gives none
   * @param tolerance the merchant's tolerance of on-chain underpayments (basis points), 0 for none:
   *     never returned to the integrator
   * @param bitcoinNode the merchant's Bitcoin node (SPEC.md §31), null when the channel names none
   *     or an invalid one (the explorer is then mempool.space)
   * @param updatedAt the record's {@code created_at}
   */
  public record Channel(
      boolean removed,
      String businessName,
      String lightningAddress,
      String registryKey,
      String registryReader,
      List<String> relays,
      String checkoutOrigin,
      int tolerance,
      BitcoinNode bitcoinNode,
      long updatedAt) {
    @Override
    public String toString() {
      return "Channel[" + (removed ? "removed" : "active") + "]";
    }
  }

  /**
   * A channel's value, as the merchant's app reads it, for what the server uses (SPEC.md §14, "A
   * valid channel value"). The other fields are the app's own and not read here.
   *
   * @param value the record's value
   * @param createdAt its event's date
   * @return the channel, or null when it is not valid
   * @throws Records.NewerFormat for a newer version
   */
  public static Channel parseChannel(JsonValue value, long createdAt) {
    JsonObject v = Json.object(value);
    if (v == null) return null;
    if (Json.isNumberAbove(v.get("version"), Constants.CHANNEL_VERSION))
      throw new Records.NewerFormat();
    if (!Json.isNumber(v.get("version"), Constants.CHANNEL_VERSION)
        || !Json.isNumber(v.get("updatedAt"), createdAt)) {
      return null;
    }
    String status = Json.string(v.get("status"));
    if ("removed".equals(status))
      return new Channel(true, null, null, null, null, null, null, 0, null, createdAt);
    if (!"active".equals(status) || !"integration".equals(Json.string(v.get("role")))) return null;
    JsonObject profile = Json.object(v.get("profile"));
    if (profile == null) return null;
    String businessName = Json.string(profile.get("businessName"));
    if (!Checkout.isBusinessName(businessName)) return null;
    String lightningAddress = Json.string(profile.get("lightningAddress"));
    if (lightningAddress == null || Urls.lightningAddressUrl(lightningAddress) == null) return null;
    // The reading key must be a point of the curve: every declaration's inside is sealed to it.
    String registryKey = Json.string(v.get("registryKey"));
    String registryReader = Json.string(v.get("registryReader"));
    if (!Text.isHex32(registryKey) || !Curve.isPublicKey(registryReader)) return null;
    List<String> relays = null;
    if (v.has("relays")) {
      relays = Relays.parseList(v.get("relays"));
      if (relays == null) return null;
    }
    return new Channel(
        false,
        businessName,
        lightningAddress,
        registryKey,
        registryReader,
        relays,
        Checkout.checkoutOriginOf(v.get("checkout")),
        // Absent or invalid, none: the channel stays valid.
        Tolerance.toleranceOf(v.get("onchainTolerance")),
        // Absent or invalid in any way, none: the channel stays valid (no new version of it).
        BitcoinNode.of(v.get("bitcoinNode")),
        createdAt);
  }

  /** What the relays of one list hold. */
  public sealed interface Lookup permits Found, NotFound, Unreachable {}

  /**
   * The channel was found.
   *
   * @param channel the channel
   */
  public record Found(Channel channel) implements Lookup {}

  /**
   * A majority of the relays answered: no channel, or only one of a newer format.
   *
   * @param newer whether one of a newer format was seen
   */
  public record NotFound(boolean newer) implements Lookup {}

  /** Fewer than a majority of the relays answered. */
  public record Unreachable() implements Lookup {}

  /**
   * The newest copy of the integration's channel on {@code relays}, signed by its team: every relay
   * at once, read as soon as a majority of them answered.
   *
   * @param pool the call's relays
   * @param access the integration key
   * @param relays the list
   * @param random the CSPRNG
   * @param logger where warnings go
   * @return what the list holds
   * @throws InterruptedException when the call is cancelled
   */
  public static Lookup lookupChannel(
      RelayPool pool, IntegrationKey access, List<String> relays, RandomBytes random, Warn logger)
      throws InterruptedException {
    byte[] key = access.key();
    String address = Derive.channelAddress(key);
    byte[] encryptionKey = Derive.channelEncryptionKey(key);
    String team = access.teamPublicKey();
    JsonObject filter =
        new JsonObject()
            .put("kinds", new JsonArray().add(Constants.RECORD_KIND))
            .put("authors", JsonArray.ofStrings(List.of(team)))
            .put("#d", JsonArray.ofStrings(List.of(address)));
    // A relay that asks to authenticate (NIP-42) gets a key of this read alone.
    RelayPool.Answers read =
        Quorum.query(
            pool,
            relays,
            filter,
            random.next(32),
            false,
            Records.recordsThatOpen((d, author) -> author.equals(team) ? encryptionKey : null),
            false);
    if (!read.outcome().answered()) return new Unreachable();
    Map<String, List<Object>> answers = read.events();
    Records.Newest<Channel> found =
        Records.newest(
            Records.asValues(answers),
            value ->
                Records.open(
                    value,
                    Constants.CHANNEL_RECORD_TYPE,
                    encryptionKey,
                    address,
                    team,
                    (v, event) -> parseChannel(v, event.createdAt())));
    Long newer = found.newestUnreadable();
    if (newer != null
        && newer > (found.newest() == null ? -1 : found.newest().event().createdAt())) {
      logger.warn(
          Warn.PREFIX
              + "the integration's channel has a newer format than this version reads; update the"
              + " package");
    }
    return found.newest() != null ? new Found(found.newest().value()) : new NotFound(newer != null);
  }

  /**
   * The integration's channel, active, and the relays it was read on.
   *
   * @param channel the channel
   * @param relays the relays it was read on
   */
  public record ChannelRead(Channel channel, List<String> relays) {}

  /**
   * The integration's channel, active, on the account's current relays (SPEC.md §14, "Reading the
   * channel"): read on the key's relays, then where the channel says the account moved (3 times at
   * most).
   *
   * @param pool the call's relays
   * @param access the integration key
   * @param random the CSPRNG
   * @param logger where warnings go
   * @return the channel and the relays it was read on
   * @throws InterruptedException when the call is cancelled
   * @throws RevokedException when it is removed
   * @throws NetworkException when fewer than a majority of a list answered
   * @throws ChannelNotFoundException when a majority of the key's relays answered without it
   */
  public static ChannelRead readChannel(
      RelayPool pool, IntegrationKey access, RandomBytes random, Warn logger)
      throws InterruptedException {
    List<String> relays = access.relays();
    Lookup lookup = lookupChannel(pool, access, relays, random, logger);
    if (lookup instanceof Unreachable) {
      throw new NetworkException("Fewer than a majority of the integration key's relays answered");
    }
    if (lookup instanceof NotFound notFound) throw new ChannelNotFoundException(notFound.newer());
    Channel channel = ((Found) lookup).channel();
    for (int move = 0; move < Constants.MAX_RELAY_MOVES; move += 1) {
      if (channel.removed() || channel.relays() == null || channel.relays().equals(relays)) break;
      List<String> next = channel.relays();
      lookup = lookupChannel(pool, access, next, random, logger);
      if (lookup instanceof Unreachable) {
        throw new NetworkException("Fewer than a majority of the merchant's relays answered");
      }
      // Not copied there (yet): the channel read so far stands, and the declarations still go to
      // the account's relays it names.
      if (lookup instanceof NotFound) break;
      channel = ((Found) lookup).channel();
      relays = next;
    }
    if (channel.removed()) throw new RevokedException();
    return new ChannelRead(channel, relays);
  }
}
