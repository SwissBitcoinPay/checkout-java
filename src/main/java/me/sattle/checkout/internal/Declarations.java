package me.sattle.checkout.internal;

import java.util.List;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Addresses;

/**
 * The declaration of an invoice in the merchant's registry of payments (SPEC.md §20), as the
 * merchant's app reads it: published on a majority of the merchant's relays before the invoice is
 * shown, so that its payment counts for this order's sale and for no other. Its format is the
 * app's, version 1; {@code rest}, in a rest invoice's, is an optional field the app ignores.
 */
public final class Declarations {
  private Declarations() {}

  /**
   * The invoice an invoice of the rest is of.
   *
   * @param address its deposit address
   * @param expiresAt its end
   */
  public record RestOf(String address, long expiresAt) {}

  /**
   * The signed declaration of an invoice for {@code sale} (SPEC.md §20): the declaration (inside,
   * NIP-44 with the inner key), then {@code {"t": "registry-declaration", "v": {"sealed": …}}}
   * padded to a multiple of 256 bytes (outside, NIP-44 with the outer key), kind 30078, {@code d}
   * the registry address of the payment hash, signed by the sale's secret.
   *
   * @param sale the sale
   * @param invoice the invoice, checked
   * @param verifyUrl its verify URL
   * @param paymentHash its payment hash
   * @param price the declared price
   * @param note the label
   * @param createdAt Unix seconds
   * @param rest the invoice whose rest it is, or null for an invoice of the order
   * @param random the CSPRNG
   * @return the event
   * @throws Nip44.PlaintextException when it does not fit a NIP-44 plaintext
   */
  public static NostrEvent declarationEvent(
      Derive.Sale sale,
      String invoice,
      String verifyUrl,
      String paymentHash,
      Checkout.DeclaredPrice price,
      String note,
      long createdAt,
      RestOf rest,
      RandomBytes random) {
    JsonObject declaration =
        new JsonObject()
            .put("version", Constants.DECLARATION_VERSION)
            .put("invoice", invoice)
            .put("verifyUrl", verifyUrl)
            .put(
                "sale",
                new JsonObject()
                    .put("key", Hex.encode(sale.key))
                    .put("fiat", price.toJson())
                    .put("note", note))
            // "By" in the app: this integration.
            .put("taker", sale.taker);
    if (rest != null) {
      // Only in a rest invoice's: the app ignores it, and counts the payment for the sale.
      declaration.put(
          "rest",
          new JsonObject().put("address", rest.address()).put("expiresAt", rest.expiresAt()));
    }
    String address = Derive.registryAddress(sale.registry.key(), paymentHash);
    String sealed = Nip44.encrypt(Json.write(declaration), sale.innerKey, random.next(32));
    return Records.seal(
        Constants.DECLARATION_RECORD_TYPE,
        new JsonObject().put("sealed", sealed),
        sale.secretKey,
        Derive.outerKey(sale.registry.key(), address, sale.author),
        address,
        createdAt,
        List.of(),
        random);
  }

  /**
   * A declaration of the sale, opened by its holder.
   *
   * @param event its event, checked
   * @param invoice the invoice
   * @param decoded the invoice, decoded
   * @param verifyUrl its verify URL
   * @param price the declared price
   * @param note the declared note
   * @param declaredAt the event's {@code created_at}
   * @param rest the invoice whose rest it is; null for an invoice of the order
   */
  public record Opened(
      NostrEvent event,
      String invoice,
      Bolt11 decoded,
      String verifyUrl,
      Checkout.DeclaredPrice price,
      String note,
      long declaredAt,
      RestOf rest) {}

  /** A declaration's {@code fiat}: minor from 0, digits 0 to 8, three letters. */
  private static Checkout.DeclaredPrice parsePrice(JsonValue value) {
    JsonObject object = Json.object(value);
    if (object == null) return null;
    Long minor = Json.safeInteger(object.get("minor"));
    if (minor == null || minor < 0) return null;
    JsonValue digits = object.get("digits");
    Long places = Json.isInteger(digits) ? Json.safeInteger(digits) : null;
    if (places == null || places < 0 || places > Constants.MAX_DECLARED_DIGITS) return null;
    String currency = Json.string(object.get("currency"));
    if (currency == null || !currency.matches("[A-Z]{3}")) return null;
    return new Checkout.DeclaredPrice(minor, (int) (long) places, currency);
  }

  /** A declaration's {@code rest}: absent (null), or the invoice it names; INVALID otherwise. */
  private static final RestOf INVALID_REST = new RestOf("", -1);

  private static RestOf restOfValue(JsonValue value) {
    if (value == null) return null;
    JsonObject object = Json.object(value);
    if (object == null) return INVALID_REST;
    String address = Json.string(object.get("address"));
    if (!Addresses.isDepositAddress(address)) return INVALID_REST;
    Long expiresAt = Json.safeInteger(object.get("expiresAt"));
    if (expiresAt == null || expiresAt < 0) return INVALID_REST;
    return new RestOf(address, expiresAt);
  }

  /**
   * What a relay sent, if it is a declaration of {@code sale} (SPEC.md §20, "Opening"): author,
   * {@code d} tag (the address of its own invoice), outer and inner encryption, signature, and the
   * sale's own key inside.
   *
   * @param value what a relay sent
   * @param sale the sale
   * @return the declaration, or null
   * @throws Records.NewerFormat for one of a newer format
   */
  public static Opened openDeclaration(JsonValue value, Derive.Sale sale) {
    String d = Records.dTagOf(value);
    if (d == null) return null;
    Records.Opened<Opened> opened =
        Records.open(
            value,
            Constants.DECLARATION_RECORD_TYPE,
            Derive.outerKey(sale.registry.key(), d, sale.author),
            d,
            sale.author,
            (outside, event) -> {
              JsonObject outer = Json.object(outside);
              String sealed = outer == null ? null : Json.string(outer.get("sealed"));
              if (sealed == null) return null;
              JsonObject v;
              try {
                v = Json.object(Json.parseOrNull(Nip44.decrypt(sealed, sale.innerKey)));
              } catch (Nip44.UnknownVersionException | Nip44.DecryptException e) {
                return null;
              }
              if (v == null) return null;
              if (Json.isNumberAbove(v.get("version"), Constants.DECLARATION_VERSION)) {
                throw new Records.NewerFormat();
              }
              String invoice = Json.string(v.get("invoice"));
              if (!Json.isNumber(v.get("version"), Constants.DECLARATION_VERSION)
                  || invoice == null) {
                return null;
              }
              String verifyUrl = Json.string(v.get("verifyUrl"));
              if (verifyUrl == null || Urls.parseHttpsUrl(verifyUrl) == null) return null;
              JsonObject saleValue = Json.object(v.get("sale"));
              if (saleValue == null) return null;
              String note = Json.string(saleValue.get("note"));
              if (!Hex.encode(sale.key).equals(Json.string(saleValue.get("key"))) || note == null) {
                return null;
              }
              Checkout.DeclaredPrice price = parsePrice(saleValue.get("fiat"));
              if (price == null) return null;
              RestOf rest = restOfValue(v.get("rest"));
              if (rest == INVALID_REST) return null;
              Bolt11 decoded = Bolt11.decode(invoice);
              if (decoded == null
                  || !decoded.network().equals("bc")
                  || decoded.wholeSats() == null) {
                return null;
              }
              if (!d.equals(Derive.registryAddress(sale.registry.key(), decoded.paymentHash()))) {
                return null;
              }
              return new Opened(
                  event, invoice, decoded, verifyUrl, price, note, event.createdAt(), rest);
            });
    if (opened == null) return null;
    if (opened.isNewer()) throw new Records.NewerFormat();
    return opened.value();
  }

  /**
   * The registry of a channel's hex fields.
   *
   * @param registryKey the registry key (hex)
   * @param registryReader the registry reader (hex)
   * @return the registry
   */
  public static Derive.Registry registryOf(String registryKey, String registryReader) {
    return new Derive.Registry(Hex.decode(registryKey), registryReader);
  }
}
