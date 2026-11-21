package me.sattle.checkout.internal;

import me.sattle.checkout.DeclaredInvoice;
import me.sattle.checkout.Fiat;
import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.Invoice;
import me.sattle.checkout.InvoiceToCheck;
import me.sattle.checkout.Merchant;
import me.sattle.checkout.PaymentStatus;
import me.sattle.checkout.RateInfo;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The JSON of the data model (SPEC.md §3), with these field names and in this order whatever the
 * language: an invoice stored by one implementation is checked by another.
 */
public final class Model {
  private Model() {}

  /** What a stored invoice that is not one fails with. */
  public static final String NOT_STORED = "not an invoice made by createInvoice";

  /**
   * The text of a JSON value, as {@code JSON.stringify} writes it.
   *
   * @param value the value
   * @return its text
   */
  public static String write(JsonValue value) {
    return Json.write(value);
  }

  private static JsonValue text(String value) {
    return value == null ? JsonNull.NULL : new JsonString(value);
  }

  /**
   * A Fiat's JSON.
   *
   * @param fiat the price, or null
   * @return its JSON, or {@code null}
   */
  public static JsonValue fiat(Fiat fiat) {
    if (fiat == null) return JsonNull.NULL;
    return new JsonObject()
        .put("amount", text(fiat.amount()))
        .put("currency", text(fiat.currency()))
        .put("minor", text(fiat.minor()))
        .put("digits", fiat.digits());
  }

  /**
   * A RateInfo's JSON.
   *
   * @param rate the rate, or null
   * @return its JSON, or {@code null}
   */
  public static JsonValue rateInfo(RateInfo rate) {
    if (rate == null) return JsonNull.NULL;
    return new JsonObject()
        .put("source", text(rate.source()))
        .put("currency", text(rate.currency()))
        .put("price", text(rate.price()))
        .put("fetchedAt", rate.fetchedAt())
        .put("time", rate.time());
  }

  /**
   * A Merchant's JSON.
   *
   * @param merchant the merchant
   * @return its JSON
   */
  public static JsonObject merchant(Merchant merchant) {
    return new JsonObject()
        .put("businessName", text(merchant.businessName()))
        .put("minSats", text(merchant.minSats()))
        .put("maxSats", text(merchant.maxSats()))
        .put("onchainAddresses", merchant.onchainAddresses());
  }

  /**
   * An Invoice's JSON.
   *
   * @param invoice the invoice
   * @return its JSON
   */
  public static JsonObject invoice(Invoice invoice) {
    return new JsonObject()
        .put("ref", text(invoice.ref()))
        .put("bolt11", text(invoice.bolt11()))
        .put("paymentHash", text(invoice.paymentHash()))
        .put("verifyUrl", text(invoice.verifyUrl()))
        .put("onchainAddress", text(invoice.onchainAddress()))
        .put("paymentUri", text(invoice.paymentUri()))
        .put("sats", text(invoice.sats()))
        .put("fiat", fiat(invoice.fiat()))
        .put("rate", rateInfo(invoice.rate()))
        .put("note", text(invoice.note()))
        .put("createdAt", invoice.createdAt())
        .put("expiresAt", invoice.expiresAt())
        .put("checkoutUrl", text(invoice.checkoutUrl()))
        .put("redirectUrl", text(invoice.redirectUrl()));
  }

  /**
   * A DeclaredInvoice's JSON.
   *
   * @param invoice the invoice
   * @return its JSON
   */
  public static JsonObject declaredInvoice(DeclaredInvoice invoice) {
    return new JsonObject()
        .put("ref", text(invoice.ref()))
        .put("bolt11", text(invoice.bolt11()))
        .put("paymentHash", text(invoice.paymentHash()))
        .put("verifyUrl", text(invoice.verifyUrl()))
        .put("onchainAddress", text(invoice.onchainAddress()))
        .put("paymentUri", text(invoice.paymentUri()))
        .put("sats", text(invoice.sats()))
        .put("fiat", fiat(invoice.fiat()))
        .put("note", text(invoice.note()))
        .put("createdAt", invoice.createdAt())
        .put("expiresAt", invoice.expiresAt())
        .put("declaredAt", invoice.declaredAt());
  }

  /**
   * A PaymentStatus's JSON (SPEC.md §3).
   *
   * @param status the status
   * @return its JSON
   */
  public static JsonObject status(PaymentStatus status) {
    JsonObject json = new JsonObject().put("state", status.state());
    if (status instanceof PaymentStatus.Open open) {
      json.put("expiresAt", open.expiresAt()).put("received", text(open.received()));
      PaymentStatus.Rest rest = open.rest();
      json.put(
          "rest",
          rest == null
              ? JsonNull.NULL
              : new JsonObject()
                  .put("sats", text(rest.sats()))
                  .put("bolt11", text(rest.bolt11()))
                  .put("paymentUri", text(rest.paymentUri())));
    } else if (status instanceof PaymentStatus.Unconfirmed unconfirmed) {
      json.put("received", text(unconfirmed.received()))
          .put("confirmations", unconfirmed.confirmations())
          .put("txids", JsonArray.ofStrings(unconfirmed.txids()));
    } else if (status instanceof PaymentStatus.PaidLightning paid) {
      json.put("method", paid.method()).put("preimage", text(paid.preimage()));
    } else if (status instanceof PaymentStatus.PaidOnchain paid) {
      json.put("method", paid.method())
          .put("received", text(paid.received()))
          .put("txids", JsonArray.ofStrings(paid.txids()));
    } else if (status instanceof PaymentStatus.PaidMixed paid) {
      json.put("method", paid.method())
          .put("received", text(paid.received()))
          .put("txids", JsonArray.ofStrings(paid.txids()))
          .put("preimages", JsonArray.ofStrings(paid.preimages()));
    } else if (status instanceof PaymentStatus.Expired expired) {
      json.put("received", text(expired.received()));
    }
    return json;
  }

  /** The fields of an invoice to check, as given (SPEC.md §3, InvoiceToCheck). */
  private record Fields(
      String ref,
      String bolt11,
      String paymentHash,
      String verifyUrl,
      String onchainAddress,
      String sats,
      long expiresAt)
      implements InvoiceToCheck {}

  /**
   * An invoice to check, as given.
   *
   * @param ref the order's reference, or null
   * @param bolt11 the BOLT11 invoice, or null
   * @param paymentHash its payment hash, or null
   * @param verifyUrl its verify URL, or null
   * @param onchainAddress the deposit address, or null
   * @param sats the amount in sats
   * @param expiresAt its end
   * @return the invoice to check
   */
  public static InvoiceToCheck invoiceToCheck(
      String ref,
      String bolt11,
      String paymentHash,
      String verifyUrl,
      String onchainAddress,
      String sats,
      long expiresAt) {
    return new Fields(ref, bolt11, paymentHash, verifyUrl, onchainAddress, sats, expiresAt);
  }

  private static InvalidOrderException notStored() {
    return new InvalidOrderException("invoice", NOT_STORED);
  }

  private static JsonObject storedObject(String json) {
    JsonObject object = Json.object(Json.parseOrNull(json));
    if (object == null) throw notStored();
    return object;
  }

  /** A field that is a string, or null (present, or absent when {@code absentIsNull}). */
  private static String nullableText(JsonObject object, String name, boolean absentIsNull) {
    if (!object.has(name)) {
      if (absentIsNull) return null;
      throw notStored();
    }
    JsonValue value = object.get(name);
    if (value == JsonNull.NULL) return null;
    String text = Json.string(value);
    if (text == null) throw notStored();
    return text;
  }

  /**
   * A field read only where a rule reads it (the order's {@code ref}, with an address): its string,
   * else null, whatever else it is; the check of the invoice decides.
   */
  private static String textIfAny(JsonObject object, String name) {
    return Json.string(object.get(name));
  }

  private static String requiredText(JsonObject object, String name) {
    String text = Json.string(object.get(name));
    if (text == null) throw notStored();
    return text;
  }

  private static long requiredInteger(JsonObject object, String name) {
    Long value = Json.safeInteger(object.get(name));
    if (value == null) throw notStored();
    return value;
  }

  private static Fiat fiatFrom(JsonValue value) {
    if (value == null || value == JsonNull.NULL) return null;
    JsonObject object = Json.object(value);
    if (object == null) throw notStored();
    Long digits = Json.safeInteger(object.get("digits"));
    if (digits == null || digits < 0 || digits > Integer.MAX_VALUE) throw notStored();
    return new Fiat(
        requiredText(object, "amount"),
        requiredText(object, "currency"),
        requiredText(object, "minor"),
        (int) (long) digits);
  }

  private static RateInfo rateFrom(JsonValue value) {
    if (value == null || value == JsonNull.NULL) return null;
    JsonObject object = Json.object(value);
    if (object == null) throw notStored();
    JsonValue time = object.get("time");
    Long timeValue = null;
    if (time != null && time != JsonNull.NULL) {
      timeValue = Json.safeInteger(time);
      if (timeValue == null) throw notStored();
    }
    return new RateInfo(
        requiredText(object, "source"),
        requiredText(object, "currency"),
        requiredText(object, "price"),
        requiredInteger(object, "fetchedAt"),
        timeValue);
  }

  /**
   * What a payment check needs of an invoice stored as JSON.
   *
   * @param json the stored JSON
   * @return the invoice to check
   */
  public static InvoiceToCheck invoiceToCheckFromJson(String json) {
    JsonObject object = storedObject(json);
    return new Fields(
        // Read with an address only (SPEC.md §22): a reference then, else nothing to the check.
        textIfAny(object, "ref"),
        nullableText(object, "bolt11", false),
        nullableText(object, "paymentHash", false),
        nullableText(object, "verifyUrl", false),
        nullableText(object, "onchainAddress", true),
        requiredText(object, "sats"),
        requiredInteger(object, "expiresAt"));
  }

  /**
   * An Invoice stored as JSON: the types of its fields checked.
   *
   * @param json the stored JSON
   * @return the invoice
   */
  public static Invoice invoiceFromJson(String json) {
    JsonObject object = storedObject(json);
    return new Invoice(
        requiredText(object, "ref"),
        nullableText(object, "bolt11", false),
        nullableText(object, "paymentHash", false),
        nullableText(object, "verifyUrl", false),
        nullableText(object, "onchainAddress", true),
        requiredText(object, "paymentUri"),
        requiredText(object, "sats"),
        fiatFrom(object.get("fiat")),
        rateFrom(object.get("rate")),
        requiredText(object, "note"),
        requiredInteger(object, "createdAt"),
        requiredInteger(object, "expiresAt"),
        nullableText(object, "checkoutUrl", true),
        nullableText(object, "redirectUrl", true));
  }

  /**
   * A DeclaredInvoice stored as JSON: the types of its fields checked.
   *
   * @param json the stored JSON
   * @return the invoice
   */
  public static DeclaredInvoice declaredInvoiceFromJson(String json) {
    JsonObject object = storedObject(json);
    return new DeclaredInvoice(
        requiredText(object, "ref"),
        nullableText(object, "bolt11", false),
        nullableText(object, "paymentHash", false),
        nullableText(object, "verifyUrl", false),
        nullableText(object, "onchainAddress", true),
        requiredText(object, "paymentUri"),
        requiredText(object, "sats"),
        fiatFrom(object.get("fiat")),
        requiredText(object, "note"),
        requiredInteger(object, "createdAt"),
        requiredInteger(object, "expiresAt"),
        requiredInteger(object, "declaredAt"));
  }
}
