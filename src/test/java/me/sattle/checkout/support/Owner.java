package me.sattle.checkout.support;

import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;

/** What the merchant's app reads of the registry: with the registry key and the reading key. */
public final class Owner {
  private Owner() {}

  /**
   * A declaration (or a lease), opened as the merchant's app does: its inside.
   *
   * @param event the event
   * @param registryKey the registry key
   * @param readerSecret the merchant's reading secret
   * @return the inside, as JSON
   */
  public static JsonObject openAsOwner(NostrEvent event, byte[] registryKey, byte[] readerSecret) {
    try {
      String address = event.tag("d");
      JsonValue outside =
          Js.parse(
              Nip44.decrypt(
                  event.content(), Derive.outerKey(registryKey, address, event.pubkey())));
      String sealed = Js.text(Js.get(outside, "v"), "sealed");
      return (JsonObject)
          Js.parse(Nip44.decrypt(sealed, Nip44.conversationKey(readerSecret, event.pubkey())));
    } catch (Nip44.DecryptException | Nip44.UnknownVersionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The outside of a record of the registry: {@code {"t": …, "v": …}}.
   *
   * @param event the event
   * @param registryKey the registry key
   * @return the outside
   */
  public static JsonObject outside(NostrEvent event, byte[] registryKey) {
    try {
      return (JsonObject)
          Js.parse(
              Nip44.decrypt(
                  event.content(), Derive.outerKey(registryKey, event.tag("d"), event.pubkey())));
    } catch (Nip44.DecryptException | Nip44.UnknownVersionException e) {
      throw new IllegalStateException(e);
    }
  }
}
