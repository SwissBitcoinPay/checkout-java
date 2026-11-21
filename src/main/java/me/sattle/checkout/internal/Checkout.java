package me.sattle.checkout.internal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Addresses;
import me.sattle.checkout.internal.onchain.BitcoinNode;

/**
 * Checkout links (SPEC.md §30): a URL of the merchant's web app, {@code
 * <origin>/pay#c.<author>.<key>.<relays>}, whose page reads the invoice from the merchant's relays,
 * shows it, checks its payment from the customer's browser and, once it is paid, sends the customer
 * to the order's redirect URL. The record is sealed with a key of the link alone (in the URL's
 * fragment, which browsers never send to the web app's host) and signed by an author of the link
 * alone, whose secret is forgotten once it is published: nobody, the integrator included, can
 * change a link afterwards. The package writes links; it also reads them (fragments, records), as
 * the page does, for the conformance vectors.
 */
public final class Checkout {
  private Checkout() {}

  private static final Pattern HTTPS_AUTHORITY =
      Pattern.compile("https://([^/?#]*).*", Pattern.DOTALL);
  private static final Pattern APP_URL =
      Pattern.compile("([a-z][a-z0-9+.-]*)://.*", Pattern.DOTALL);
  private static final Pattern ORIGIN = Pattern.compile("https://[^/?#]*");
  private static final Pattern MARKER = Pattern.compile("[a-z0-9]{1,8}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  /**
   * Whether {@code value} is a checkout origin: {@code https://}, a host as in a relay address, an
   * optional port, nothing else.
   *
   * @param value a text, or null
   * @return true when it is one
   */
  public static boolean isCheckoutOrigin(String value) {
    return Urls.parseHttpsUrl(value) != null && ORIGIN.matcher(value).matches();
  }

  /**
   * The origin a channel's {@code checkout} field gives.
   *
   * @param checkout the field, or null when absent
   * @return the origin, or null when it gives none or an invalid one
   */
  public static String checkoutOriginOf(JsonValue checkout) {
    JsonObject object = Json.object(checkout);
    if (object == null) return null;
    String origin = Json.string(object.get("origin"));
    return isCheckoutOrigin(origin) ? origin : null;
  }

  /**
   * What the page shows of a redirect URL ("Redirecting to …"): the host and port of an https URL
   * as written, the scheme and {@code ://} of an app's link.
   *
   * @param value a text, or null
   * @return what is shown, or null when it is not a redirect URL (nothing is repaired)
   */
  public static String redirectShown(String value) {
    if (value == null
        || value.length() > Constants.MAX_REDIRECT_URL_LENGTH
        || !Urls.isUrlText(value)) {
      return null;
    }
    if (value.startsWith("https://")) {
      if (Urls.parseHttpsUrl(value) == null) return null;
      Matcher authority = HTTPS_AUTHORITY.matcher(value);
      return authority.matches() ? authority.group(1) : null;
    }
    Matcher app = APP_URL.matcher(value);
    if (!app.matches()) return null;
    String scheme = app.group(1);
    if (scheme.length() > Constants.MAX_REDIRECT_SCHEME_LENGTH
        || scheme.equals("https")
        || Constants.REFUSED_REDIRECT_SCHEMES.contains(scheme)) {
      return null;
    }
    return scheme + "://";
  }

  /**
   * A link author's secret: 32 random bytes, drawn again until they are a secp256k1 secret key.
   *
   * @param random the CSPRNG
   * @return the secret
   */
  public static byte[] authorSecret(RandomBytes random) {
    for (; ; ) {
      byte[] secret = random.next(Constants.KEY_BYTES);
      if (Curve.isSecretKey(secret)) return secret;
    }
  }

  /**
   * The URL of a link: {@code <origin>/pay#c.<author>.<key>.<relays>}.
   *
   * @param origin the checkout origin
   * @param author the link's author (hex)
   * @param linkKey the link key
   * @param relays where its record is published
   * @return the URL
   */
  public static String checkoutUrl(
      String origin, String author, byte[] linkKey, List<String> relays) {
    return origin
        + Constants.CHECKOUT_PATH
        + "#"
        + String.join(
            ".",
            Constants.CHECKOUT_MARKER,
            Base64Strict.encodeUrl(Hex.decode(author)),
            Base64Strict.encodeUrl(linkKey),
            Relays.encodeList(relays));
  }

  /**
   * The price a sale is declared with, as the declaration's JSON holds it: {@code minor} a JSON
   * number. For a sale in sats: {@code {"minor": <sats>, "digits": 0, "currency": "SAT"}}.
   *
   * @param minor the minor units (at most 2^53 − 1)
   * @param digits the decimals
   * @param currency ISO 4217, or {@code SAT}
   */
  public record DeclaredPrice(long minor, int digits, String currency) {
    /**
     * Its JSON.
     *
     * @return {@code {"minor": …, "digits": …, "currency": …}}
     */
    public JsonObject toJson() {
      return new JsonObject().put("minor", minor).put("digits", digits).put("currency", currency);
    }
  }

  /**
   * The rest author and rest key a link's record carries.
   *
   * @param author the rest author (hex)
   * @param key the rest key
   */
  public record RestRef(String author, byte[] key) {
    @Override
    public String toString() {
      return "RestRef[author=" + author + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof RestRef
          && author.equals(((RestRef) other).author)
          && java.util.Arrays.equals(key, ((RestRef) other).key);
    }

    @Override
    public int hashCode() {
      return author.hashCode();
    }
  }

  /**
   * The record of a link to an invoice (SPEC.md §30, "The record"): version 2 with an address, else
   * version 1.
   *
   * @param businessName the channel's business name
   * @param invoice the Lightning invoice, or null (on-chain only)
   * @param verifyUrl its verify URL, or null
   * @param onchainAddress the deposit address, or null
   * @param sats the Invoice's sats
   * @param createdAt the Invoice's date
   * @param expiresAt the Invoice's end
   * @param fiat the declared price, or null for an order in sats
   * @param note the label
   * @param redirectUrl the order's redirect URL, or null
   * @param rest with an address: its rest author and rest key, or null
   * @param node with an address: the merchant's Bitcoin node the page reads the chain from (the
   *     channel's with {@code payers} true, {@link BitcoinNode#recordNodeFor}), or null for
   *     mempool.space; never in a record of version 1
   * @return the record's value
   */
  public static JsonObject checkoutValue(
      String businessName,
      String invoice,
      String verifyUrl,
      String onchainAddress,
      long sats,
      long createdAt,
      long expiresAt,
      DeclaredPrice fiat,
      String note,
      String redirectUrl,
      RestRef rest,
      BitcoinNode.RecordNode node) {
    JsonValue price = fiat == null ? JsonNull.NULL : fiat.toJson();
    if (onchainAddress == null) {
      return new JsonObject()
          .put("version", Constants.CHECKOUT_VERSION)
          .put("businessName", businessName)
          .put("invoice", invoice)
          .put("verifyUrl", verifyUrl)
          .put("fiat", price)
          .put("note", note)
          .put("redirectUrl", redirectUrl);
    }
    JsonObject value =
        new JsonObject()
            .put("version", Constants.CHECKOUT_ONCHAIN_VERSION)
            .put("businessName", businessName)
            .put("invoice", invoice)
            .put("verifyUrl", verifyUrl)
            .put("onchainAddress", onchainAddress)
            .put("sats", sats)
            .put("createdAt", createdAt)
            .put("expiresAt", expiresAt)
            .put("fiat", price)
            .put("note", note)
            .put("redirectUrl", redirectUrl);
    if (rest != null) {
      value.put("restAuthor", rest.author()).put("restKey", Hex.encode(rest.key()));
    }
    // Absent, never null, without a node for the page (a page that does not know it ignores it:
    // no new version of the record).
    if (node != null) value.put("bitcoinNode", node.toJson());
    return value;
  }

  /**
   * A new link, before it is published.
   *
   * @param url its URL
   * @param event its record's event
   * @param secretKey the author's secret: it answers the relays' authentication while the event is
   *     published, then is dropped; never stored, returned to the caller nor logged
   */
  public record NewLink(String url, NostrEvent event, byte[] secretKey) {
    @Override
    public String toString() {
      return "NewLink[event=" + event.id() + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof NewLink && event.equals(((NewLink) other).event);
    }

    @Override
    public int hashCode() {
      return event.hashCode();
    }
  }

  /**
   * A new link to the invoice {@code value} holds, ending {@code expiresAt}: a fresh link key and
   * author, the record signed at {@code createdAt}, dropped by the relays a week after the
   * invoice's end, and the URL on {@code origin} naming {@code relays}.
   *
   * @param origin the checkout origin
   * @param relays where it is to be published
   * @param value the record's value
   * @param expiresAt the invoice's end
   * @param createdAt Unix seconds
   * @param random the CSPRNG
   * @return the link
   * @throws me.sattle.checkout.internal.crypto.Nip44.PlaintextException when it does not fit a
   *     NIP-44 plaintext
   */
  public static NewLink newCheckoutLink(
      String origin,
      List<String> relays,
      JsonObject value,
      long expiresAt,
      long createdAt,
      RandomBytes random) {
    byte[] linkKey = random.next(Constants.KEY_BYTES);
    byte[] secretKey = authorSecret(random);
    NostrEvent event =
        Records.seal(
            Constants.CHECKOUT_RECORD_TYPE,
            value,
            secretKey,
            Derive.checkoutEncryptionKey(linkKey),
            Derive.checkoutAddress(linkKey),
            createdAt,
            List.of(
                List.of("expiration", Long.toString(expiresAt + Constants.CHECKOUT_LIFE_SECONDS))),
            random);
    return new NewLink(
        checkoutUrl(origin, Curve.publicKeyOf(secretKey), linkKey, relays), event, secretKey);
  }

  /** What a fragment names (SPEC.md §30, "Reading the fragment"). */
  public sealed interface Fragment permits Link, Newer, Invalid, Other {}

  /**
   * A checkout link of this version.
   *
   * @param author the author (hex)
   * @param linkKey the link key
   * @param relays where its record is
   */
  public record Link(String author, byte[] linkKey, List<String> relays) implements Fragment {
    @Override
    public String toString() {
      return "Link[author=" + author + ", relays=" + relays + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Link
          && author.equals(((Link) other).author)
          && java.util.Arrays.equals(linkKey, ((Link) other).linkKey)
          && relays.equals(((Link) other).relays);
    }

    @Override
    public int hashCode() {
      return author.hashCode();
    }
  }

  /** A link of a newer format: the page asks to be updated. */
  public record Newer() implements Fragment {}

  /** An invalid link. */
  public record Invalid() implements Fragment {}

  /** Not a checkout link: another link of the page. */
  public record Other() implements Fragment {}

  /**
   * The link a URL's fragment (with or without {@code #}) names.
   *
   * @param fragment the fragment
   * @return what it names
   */
  public static Fragment parseFragment(String fragment) {
    String[] parts = (fragment.startsWith("#") ? fragment.substring(1) : fragment).split("\\.", -1);
    String marker = parts[0];
    if (!marker.equals(Constants.CHECKOUT_MARKER)) {
      return MARKER.matcher(marker).matches() ? new Newer() : new Other();
    }
    if (parts.length != 4) return new Invalid();
    byte[] author = Base64Strict.decodeUrl(parts[1]);
    byte[] linkKey = Base64Strict.decodeUrl(parts[2]);
    List<String> relays = Relays.decodeList(parts[3]);
    if (author == null
        || author.length != Constants.KEY_BYTES
        || linkKey == null
        || linkKey.length != Constants.KEY_BYTES
        || relays == null) {
      return new Invalid();
    }
    String authorHex = Hex.encode(author);
    if (!Curve.isPublicKey(authorHex)) return new Invalid();
    return new Link(authorHex, linkKey, relays);
  }

  /**
   * A link's record, checked: what the page shows.
   *
   * @param businessName the business's name
   * @param invoice the Lightning invoice, null for an order paid on-chain only
   * @param paymentHash its payment hash, or null
   * @param verifyUrl its verify URL, or null
   * @param onchainAddress the deposit address, null without one (version 1)
   * @param sats whole sats: the invoice's amount, and what the address must receive
   * @param createdAt the Invoice's date
   * @param expiresAt the Invoice's end
   * @param fiat the declared price, or null
   * @param note the label
   * @param redirectUrl the redirect URL, or null
   * @param redirectShown what the page shows of it, or null
   * @param rest the rest author and rest key of a record of version 2 that carries them, else null
   * @param bitcoinNode the merchant's Bitcoin node the page reads the chain from, with its
   *     fallback: of a record of version 2 whose {@code bitcoinNode} is valid; null for
   *     mempool.space (none, one set aside, or a record of version 1, which ignores it)
   */
  public record CheckoutRecord(
      String businessName,
      String invoice,
      String paymentHash,
      String verifyUrl,
      String onchainAddress,
      long sats,
      long createdAt,
      long expiresAt,
      DeclaredPrice fiat,
      String note,
      String redirectUrl,
      String redirectShown,
      RestRef rest,
      BitcoinNode.RecordNode bitcoinNode) {}

  /** A record's {@code fiat}: null, or a declared price; INVALID when it is neither. */
  private static final DeclaredPrice INVALID_PRICE = new DeclaredPrice(-1, -1, "");

  private static DeclaredPrice parseLinkPrice(JsonValue value) {
    if (value == JsonNull.NULL) return null;
    JsonObject object = Json.object(value);
    if (object == null) return INVALID_PRICE;
    Long minor = Json.safeInteger(object.get("minor"));
    if (minor == null || minor < 1) return INVALID_PRICE;
    JsonValue digits = object.get("digits");
    Long places = Json.isInteger(digits) ? Json.safeInteger(digits) : null;
    if (places == null || places < 0 || places > Constants.MAX_DECLARED_DIGITS)
      return INVALID_PRICE;
    String currency = Json.string(object.get("currency"));
    if (currency == null
        || !CURRENCY.matcher(currency).matches()
        || currency.equals(Constants.SATS_CURRENCY)) {
      return INVALID_PRICE;
    }
    return new DeclaredPrice(minor, (int) (long) places, currency);
  }

  /**
   * Whether {@code name} is a business name: a well-formed string, not empty once ASCII whitespace
   * is trimmed, at most 200 code points.
   *
   * @param name a text, or null
   * @return true when it is one
   */
  public static boolean isBusinessName(String name) {
    return name != null
        && Text.isWellFormed(name)
        && !Text.trimAscii(name).isEmpty()
        && Text.codePointLength(name) <= Constants.MAX_BUSINESS_NAME_LENGTH;
  }

  /**
   * A record's value, checked as the page reads it (SPEC.md §30, "Reading"): version 1 or 2.
   *
   * @param value the record's value
   * @return the record, or null when it is not valid
   * @throws Records.NewerFormat for a newer version
   */
  public static CheckoutRecord parseValue(JsonValue value) {
    JsonObject v = Json.object(value);
    if (v == null) return null;
    JsonValue version = v.get("version");
    if (Json.isNumberAbove(version, Constants.CHECKOUT_ONCHAIN_VERSION)) {
      throw new Records.NewerFormat();
    }
    boolean first = Json.isNumber(version, Constants.CHECKOUT_VERSION);
    boolean second = Json.isNumber(version, Constants.CHECKOUT_ONCHAIN_VERSION);
    if (!first && !second) return null;
    String businessName = Json.string(v.get("businessName"));
    if (!isBusinessName(businessName)) return null;
    JsonValue invoiceValue = v.get("invoice");
    JsonValue verifyValue = v.get("verifyUrl");
    String invoice = null;
    String verifyUrl = null;
    Bolt11 decoded = null;
    if (first || invoiceValue != JsonNull.NULL || verifyValue != JsonNull.NULL) {
      invoice = Json.string(invoiceValue);
      if (invoice == null) return null;
      decoded = Bolt11.decode(invoice);
      if (decoded == null || !decoded.network().equals("bc") || decoded.wholeSats() == null)
        return null;
      verifyUrl = Json.string(verifyValue);
      if (verifyUrl == null || Urls.parseHttpsUrl(verifyUrl) == null) return null;
    }
    String onchainAddress = null;
    RestRef rest = null;
    BitcoinNode.RecordNode node = null;
    long sats;
    long createdAt;
    long expiresAt;
    if (second) {
      String address = Json.string(v.get("onchainAddress"));
      if (!Addresses.isDepositAddress(address)) return null;
      Long satsValue = Json.safeInteger(v.get("sats"));
      if (satsValue == null || satsValue < 1 || satsValue > Constants.MAX_SATS) return null;
      Long created = Json.safeInteger(v.get("createdAt"));
      Long expires = Json.safeInteger(v.get("expiresAt"));
      if (created == null || expires == null || created >= expires) return null;
      if (decoded != null
          && (decoded.wholeSats() != (long) satsValue
              || decoded.timestamp() != created
              || decoded.expiresAt() != expires)) {
        return null;
      }
      onchainAddress = address;
      sats = satsValue;
      createdAt = created;
      expiresAt = expires;
      if (v.has("restAuthor") || v.has("restKey")) {
        String restAuthor = Json.string(v.get("restAuthor"));
        String restKey = Json.string(v.get("restKey"));
        if (!Curve.isPublicKey(restAuthor) || !Text.isHex32(restKey)) return null;
        rest = new RestRef(restAuthor, Hex.decode(restKey));
      }
      // A node of the Internet with its fallback; anything else set aside (mempool.space), the
      // record valid. In version 1, ignored.
      node = BitcoinNode.RecordNode.of(v.get("bitcoinNode"));
    } else {
      sats = decoded.wholeSats();
      createdAt = decoded.timestamp();
      expiresAt = decoded.expiresAt();
    }
    JsonValue fiatValue = v.get("fiat");
    if (fiatValue == null) return null;
    DeclaredPrice fiat = parseLinkPrice(fiatValue);
    if (fiat == INVALID_PRICE) return null;
    String note = Json.string(v.get("note"));
    if (note == null
        || !Text.isWellFormed(note)
        || Text.hasControlCharacter(note)
        || Text.codePointLength(note) < 1
        || Text.codePointLength(note) > Constants.MAX_NOTE_LENGTH) {
      return null;
    }
    JsonValue redirectValue = v.get("redirectUrl");
    String redirectUrl;
    String shown;
    if (redirectValue == JsonNull.NULL) {
      redirectUrl = null;
      shown = null;
    } else {
      redirectUrl = Json.string(redirectValue);
      shown = redirectShown(redirectUrl);
      if (shown == null) return null;
    }
    return new CheckoutRecord(
        businessName,
        invoice,
        decoded == null ? null : decoded.paymentHash(),
        verifyUrl,
        onchainAddress,
        sats,
        createdAt,
        expiresAt,
        fiat,
        note,
        redirectUrl,
        shown,
        rest,
        node);
  }
}
