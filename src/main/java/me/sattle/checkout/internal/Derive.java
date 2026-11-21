package me.sattle.checkout.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.crypto.Nip44;

/**
 * The keys an integration's server derives (SPEC.md §10, §30, §31): HKDF-SHA256, 32 bytes, salt and
 * info in UTF-8. They never change: the merchant's app derives the same keys. Every one is a
 * secret, but the rest authors and rest keys written in checkout links' records: no {@code
 * toString} of this file shows one.
 */
public final class Derive {
  private Derive() {}

  /**
   * HKDF-SHA256 of {@code secret}, 32 bytes.
   *
   * @param secret the input keying material
   * @param salt the salt (UTF-8)
   * @param info the info (UTF-8)
   * @return 32 bytes
   */
  public static byte[] key(byte[] secret, String salt, String info) {
    return Hashes.hkdf(secret, salt, info);
  }

  /** The channel address ({@code d}, hex). */
  public static String channelAddress(byte[] integrationKey) {
    return Hex.encode(
        key(integrationKey, Constants.INTEGRATION_SALT, Constants.CHANNEL_ADDRESS_INFO));
  }

  /** The channel encryption key: the conversation key of the channel, the lot and the witnesses. */
  public static byte[] channelEncryptionKey(byte[] integrationKey) {
    return key(integrationKey, Constants.INTEGRATION_SALT, Constants.CHANNEL_ENCRYPTION_INFO);
  }

  /** The lot address ({@code d}, hex). */
  public static String lotAddress(byte[] integrationKey) {
    return Hex.encode(key(integrationKey, Constants.INTEGRATION_SALT, Constants.LOT_ADDRESS_INFO));
  }

  /** The taker key, which marks the integration's declarations. */
  public static byte[] takerKey(byte[] integrationKey) {
    return key(integrationKey, Constants.INTEGRATION_SALT, Constants.TAKER_INFO);
  }

  /** The taker tag of a sale author: the first 16 bytes of HMAC(taker key, its bytes), hex. */
  public static String takerTag(byte[] takerKey, String saleAuthor) {
    return Hex.encode(
        Arrays.copyOf(
            Hashes.hmacSha256(takerKey, Hex.decode(saleAuthor)), Constants.TAKER_TAG_BYTES));
  }

  /** The registry address of the payment hash {@code paymentHash} (hex). */
  public static String registryAddress(byte[] registryKey, String paymentHash) {
    return Hex.encode(Hashes.hmacSha256(registryKey, Hex.decode(paymentHash)));
  }

  /** The registry address of a deposit address: HMAC(registry key, "onchain/v1/" + address). */
  public static String leaseAddress(byte[] registryKey, String address) {
    return Hex.encode(
        Hashes.hmacSha256(
            registryKey,
            (Constants.LEASE_ADDRESS_PREFIX + address).getBytes(StandardCharsets.UTF_8)));
  }

  /** The outer key of a record at {@code address} by {@code author}. */
  public static byte[] outerKey(byte[] registryKey, String address, String author) {
    return key(registryKey, Constants.DECLARATION_SALT, Constants.outerInfo(address, author));
  }

  /** The link address of a checkout link's key ({@code d}, hex). */
  public static String checkoutAddress(byte[] linkKey) {
    return Hex.encode(key(linkKey, Constants.CHECKOUT_SALT, Constants.CHECKOUT_ADDRESS_INFO));
  }

  /** The link encryption key of a checkout link's key. */
  public static byte[] checkoutEncryptionKey(byte[] linkKey) {
    return key(linkKey, Constants.CHECKOUT_SALT, Constants.CHECKOUT_ENCRYPTION_INFO);
  }

  /** The rest record address of the invoice whose payment hash is {@code paymentHash}. */
  public static String restRecordAddress(byte[] restKey, String paymentHash) {
    return Hex.encode(
        key(restKey, Constants.CHECKOUT_SALT, Constants.restAddressInfo(paymentHash)));
  }

  /** The acceptance record address of the invoice whose rest key is {@code restKey}. */
  public static String acceptanceRecordAddress(byte[] restKey) {
    return Hex.encode(key(restKey, Constants.CHECKOUT_SALT, Constants.ACCEPTANCE_ADDRESS_INFO));
  }

  /**
   * The merchant's acceptance address ({@code d}, hex) of the invoice of the sale {@code
   * saleAuthor} with the deposit address {@code address} ending at {@code expiresAt} (SPEC.md §31,
   * "The merchant's acceptance"): one per invoice.
   */
  public static String merchantAcceptanceAddress(
      byte[] integrationKey, String saleAuthor, String address, long expiresAt) {
    return Hex.encode(
        key(
            integrationKey,
            Constants.INTEGRATION_SALT,
            Constants.merchantAcceptanceInfo(saleAuthor, address, expiresAt)));
  }

  /**
   * The merchant's registry, as the channel gives it.
   *
   * @param key the registry key (32 bytes)
   * @param reader the public half of the merchant's reading key (hex)
   */
  public record Registry(byte[] key, String reader) {
    @Override
    public String toString() {
      return "Registry[reader=" + reader + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Registry
          && Arrays.equals(key, ((Registry) other).key)
          && reader.equals(((Registry) other).reader);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(key) + reader.hashCode();
    }
  }

  /**
   * The sale of an order: its keys, its taker tag and the conversation key (ECDH) of its
   * declarations' and leases' inside with the merchant's reading key. Secrets but its author.
   */
  public static final class Sale {
    /** The order's reference. */
    public final String ref;

    /** The sale key: written in its declarations, which the merchant reads. */
    public final byte[] key;

    /** The sale secret: it signs the sale's records. */
    public final byte[] secretKey;

    /** The sale author (hex). */
    public final String author;

    /** The taker tag. */
    public final String taker;

    /** The merchant's registry. */
    public final Registry registry;

    /** The inner key: NIP-44 conversation key of the sale secret and the registry reader. */
    public final byte[] innerKey;

    private Sale(
        String ref,
        byte[] key,
        byte[] secretKey,
        String author,
        String taker,
        Registry registry,
        byte[] innerKey) {
      this.ref = ref;
      this.key = key;
      this.secretKey = secretKey;
      this.author = author;
      this.taker = taker;
      this.registry = registry;
      this.innerKey = innerKey;
    }

    /**
     * The sale key of {@code ref}.
     *
     * @param integrationKey the integration's key
     * @param ref the order's reference
     * @return its sale key
     */
    public static byte[] saleKey(byte[] integrationKey, String ref) {
      return key(integrationKey, Constants.INTEGRATION_SALT, Constants.saleInfo(ref));
    }

    /**
     * The sale secret of a sale key.
     *
     * @param saleKey the sale key
     * @return its Nostr secret key
     */
    public static byte[] saleSecret(byte[] saleKey) {
      return key(saleKey, Constants.SALE_SALT, Constants.SALE_NOSTR_SECRET_INFO);
    }

    /**
     * The sale of {@code ref}, derived once.
     *
     * @param integrationKey the integration's key
     * @param registry the merchant's registry
     * @param ref the order's reference
     * @return the sale
     */
    public static Sale of(byte[] integrationKey, Registry registry, String ref) {
      byte[] key = saleKey(integrationKey, ref);
      byte[] secretKey = saleSecret(key);
      String author = Curve.publicKeyOf(secretKey);
      return new Sale(
          ref,
          key,
          secretKey,
          author,
          takerTag(takerKey(integrationKey), author),
          registry,
          Nip44.conversationKey(secretKey, registry.reader()));
    }

    @Override
    public String toString() {
      return "Sale[author=" + author + "]";
    }
  }

  /**
   * The keys of the integration's witnesses (SPEC.md §31, "Witnesses"). Secrets but the author.
   *
   * @param secretKey the seen secret: it signs them
   * @param author the seen author (hex)
   * @param addressKey the seen address key: their addresses and tags
   * @param encryptionKey the channel's encryption key: their conversation key
   */
  public record SeenKeys(byte[] secretKey, String author, byte[] addressKey, byte[] encryptionKey) {
    /**
     * The witnesses' keys of an integration.
     *
     * @param integrationKey the integration's key
     * @return its seen keys
     */
    public static SeenKeys of(byte[] integrationKey) {
      byte[] secret = key(integrationKey, Constants.INTEGRATION_SALT, Constants.SEEN_SECRET_INFO);
      return new SeenKeys(
          secret,
          Curve.publicKeyOf(secret),
          key(integrationKey, Constants.INTEGRATION_SALT, Constants.SEEN_ADDRESS_KEY_INFO),
          channelEncryptionKey(integrationKey));
    }

    @Override
    public String toString() {
      return "SeenKeys[author=" + author + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SeenKeys && author.equals(((SeenKeys) other).author);
    }

    @Override
    public int hashCode() {
      return author.hashCode();
    }
  }

  /**
   * The keys of the rest records of the invoice of {@code ref} with the deposit address and the end
   * (SPEC.md §31, "Rest records"): secrets but the rest author and the rest key, which its checkout
   * links' records carry.
   *
   * @param key the rest key
   * @param secretKey the rest secret: it signs the rest and acceptance records
   * @param author the rest author (hex)
   * @param encryptionKey the rest encryption key
   */
  public record RestKeys(byte[] key, byte[] secretKey, String author, byte[] encryptionKey) {
    /**
     * The rest keys of an invoice.
     *
     * @param integrationKey the integration's key
     * @param ref the order's reference
     * @param address the invoice's deposit address
     * @param expiresAt the invoice's end
     * @return its rest keys
     */
    public static RestKeys of(byte[] integrationKey, String ref, String address, long expiresAt) {
      byte[] key =
          Derive.key(
              integrationKey,
              Constants.INTEGRATION_SALT,
              Constants.restKeyInfo(ref, address, expiresAt));
      byte[] secret =
          Derive.key(
              integrationKey,
              Constants.INTEGRATION_SALT,
              Constants.restSecretInfo(ref, address, expiresAt));
      return new RestKeys(
          key,
          secret,
          Curve.publicKeyOf(secret),
          Derive.key(key, Constants.CHECKOUT_SALT, Constants.REST_ENCRYPTION_INFO));
    }

    /**
     * The keys of a reader of rest records: the rest key, the author and the encryption key alone.
     *
     * @param key the rest key
     * @param author the rest author
     * @return the keys, without the secret
     */
    public static RestKeys reader(byte[] key, String author) {
      return new RestKeys(
          key,
          null,
          author,
          Derive.key(key, Constants.CHECKOUT_SALT, Constants.REST_ENCRYPTION_INFO));
    }

    @Override
    public String toString() {
      return "RestKeys[author=" + author + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof RestKeys && author.equals(((RestKeys) other).author);
    }

    @Override
    public int hashCode() {
      return author.hashCode();
    }
  }
}
