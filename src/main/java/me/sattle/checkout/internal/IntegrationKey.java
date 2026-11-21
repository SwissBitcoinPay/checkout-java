package me.sattle.checkout.internal;

import java.util.List;
import me.sattle.checkout.InvalidKeyException;
import me.sattle.checkout.internal.crypto.Curve;

/**
 * An integration's secret key, as the merchant's app writes it (SPEC.md §8): {@code
 * sattleme_ck_<team>.<key>.<relays>}, each part strict base64url. A secret: its string and debug
 * representation leave the key out, and no message ever holds it.
 */
public final class IntegrationKey {
  // Transient, as every field that leads to the key (SattlemeClient's).
  private final transient String teamPublicKey;
  private final transient byte[] key;
  private final transient List<String> relays;

  private IntegrationKey(String teamPublicKey, byte[] key, List<String> relays) {
    this.teamPublicKey = teamPublicKey;
    this.key = key;
    this.relays = relays;
  }

  /**
   * Reads an integration's secret key: anything else is {@link InvalidKeyException}, whose message
   * never holds the key.
   *
   * @param text the key
   * @return what it holds
   * @throws InvalidKeyException when it is not one
   */
  public static IntegrationKey parse(String text) {
    if (text == null || !text.startsWith(Constants.INTEGRATION_KEY_PREFIX)) {
      throw new InvalidKeyException();
    }
    String[] parts = text.substring(Constants.INTEGRATION_KEY_PREFIX.length()).split("\\.", -1);
    if (parts.length < 3) throw new InvalidKeyException("Integration key cut short");
    if (parts.length > 3) throw new InvalidKeyException();
    byte[] team = Base64Strict.decodeUrl(parts[0]);
    byte[] key = Base64Strict.decodeUrl(parts[1]);
    List<String> relays = Relays.decodeList(parts[2]);
    if (team == null
        || team.length != Constants.KEY_BYTES
        || key == null
        || key.length != Constants.KEY_BYTES
        || relays == null) {
      throw new InvalidKeyException("Damaged integration key");
    }
    String teamPublicKey = Hex.encode(team);
    if (!Curve.isPublicKey(teamPublicKey)) throw new InvalidKeyException("Damaged integration key");
    return new IntegrationKey(teamPublicKey, key, relays);
  }

  /**
   * The team's public key: the author of the integration's channel.
   *
   * @return hex x-only
   */
  public String teamPublicKey() {
    return teamPublicKey;
  }

  /**
   * The integration's key (32 bytes). A secret: never logged.
   *
   * @return a copy of it
   */
  public byte[] key() {
    return key.clone();
  }

  /**
   * Where its channel was published when the key was given.
   *
   * @return {@code wss://} URLs
   */
  public List<String> relays() {
    return relays;
  }

  @Override
  public String toString() {
    return "IntegrationKey[team=" + teamPublicKey + ", relays=" + relays + "]";
  }
}
