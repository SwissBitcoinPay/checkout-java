package me.sattle.checkout.internal;

import java.util.List;
import java.util.regex.Pattern;
import me.sattle.checkout.Fiat;
import me.sattle.checkout.InvalidOptionException;
import me.sattle.checkout.InvalidOrderException;
import me.sattle.checkout.InvoiceToCheck;
import me.sattle.checkout.Order;
import me.sattle.checkout.RateSource;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.onchain.Addresses;

/**
 * What a caller gives, checked before any request (SPEC.md §17, §22): an order, a stored invoice,
 * an option. Refused, never repaired.
 */
public final class Orders {
  private Orders() {}

  /** An order's reference: 1 to 64 characters {@code A–Z a–z 0–9 . _ : -}. */
  public static final Pattern REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  /** What a reference must be. */
  public static final String REF_RULE = "1 to 64 characters A-Z a-z 0-9 . _ : -";

  private static final Pattern SATS = Pattern.compile("[0-9]{1,18}");

  private static final Pattern STORED_SATS = Pattern.compile("[0-9]{1,16}");

  /**
   * An order, checked.
   *
   * @param ref the reference
   * @param sats the amount in sats, or null when priced in a currency
   * @param fiat the price in a currency, or null
   * @param rateSources the rate sources of an order in a currency, in order; null in sats
   * @param durationSeconds seconds the invoice can be paid
   * @param label the note, else the reference
   * @param checkout whether it gets a checkout link
   * @param redirectUrl with {@code checkout} only: the redirect URL, else null
   */
  public record CheckedOrder(
      String ref,
      Long sats,
      Fiat fiat,
      List<RateSource> rateSources,
      int durationSeconds,
      String label,
      boolean checkout,
      String redirectUrl) {}

  /**
   * {@code ref}, checked.
   *
   * @param ref the reference
   * @return it
   * @throws InvalidOrderException ({@code ref}) unless it is 1 to 64 characters of the rule
   */
  public static String checkRef(String ref) {
    // A string only: every order without one would otherwise be one sale.
    if (ref == null || !REF.matcher(ref).matches())
      throw new InvalidOrderException("ref", REF_RULE);
    return ref;
  }

  /**
   * The order's parameters, checked (SPEC.md §17, "The order").
   *
   * @param order the order
   * @param clientRateSources the client's: an order in a currency without its own uses them
   * @return the order, checked
   * @throws InvalidOrderException naming the parameter
   */
  public static CheckedOrder checkOrder(Order order, List<RateSource> clientRateSources) {
    if (order == null) {
      throw new InvalidOrderException("order", "expected an Order (ref, sats or fiat…)");
    }
    String ref = checkRef(order.ref());
    if ((order.sats() == null) == !order.hasFiat()) {
      throw new InvalidOrderException("sats", "give either sats or fiat");
    }
    Long sats = order.sats() == null ? null : checkSats(order.sats());
    Fiat fiat = order.hasFiat() ? Prices.parseFiat(order.fiatAmount(), order.fiatCurrency()) : null;
    List<RateSource> rateSources = null;
    if (order.rateSources() != null) {
      if (fiat == null) {
        throw new InvalidOrderException(
            "rateSource", "only for an order priced in a currency (fiat)");
      }
      rateSources = RateSources.checkList(order.rateSources());
      if (rateSources == null) throw new InvalidOrderException("rateSource", RateSources.RULE);
    } else if (fiat != null) {
      rateSources = clientRateSources;
    }
    int durationSeconds =
        order.durationSeconds() == null
            ? Constants.DEFAULT_DURATION_SECONDS
            : order.durationSeconds();
    if (durationSeconds < Constants.MIN_DURATION_SECONDS
        || durationSeconds > Constants.MAX_DURATION_SECONDS) {
      throw new InvalidOrderException(
          "durationSeconds",
          Constants.MIN_DURATION_SECONDS + " to " + Constants.MAX_DURATION_SECONDS + " seconds");
    }
    String label = labelOf(order.note(), ref);
    String redirectUrl = null;
    if (order.redirectUrl() != null) {
      if (!order.checkout())
        throw new InvalidOrderException("redirectUrl", "only with checkout: true");
      if (Checkout.redirectShown(order.redirectUrl()) == null) {
        throw new InvalidOrderException(
            "redirectUrl",
            "an https URL (lowercase host, no user name nor password) or an app's link"
                + " (scheme://…), 2048 characters at most, without spaces nor other characters to"
                + " escape");
      }
      redirectUrl = order.redirectUrl();
    }
    return new CheckedOrder(
        ref, sats, fiat, rateSources, durationSeconds, label, order.checkout(), redirectUrl);
  }

  /** Sats: a string of 1 to 18 digits, from 1 to 21 million bitcoins. */
  private static long checkSats(String value) {
    if (!SATS.matcher(value).matches()) {
      throw new InvalidOrderException(
          "sats", "a whole number of sats, from 1 to " + Constants.MAX_SATS);
    }
    long sats = Long.parseLong(value);
    if (sats < 1 || sats > Constants.MAX_SATS) {
      throw new InvalidOrderException(
          "sats", "a whole number of sats, from 1 to " + Constants.MAX_SATS);
    }
    return sats;
  }

  /**
   * The note, without the spaces (U+0020) around it, else the reference: 100 code points at most,
   * well-formed, without control characters.
   *
   * @param note the note, or null
   * @param ref the reference
   * @return the label
   */
  public static String labelOf(String note, String ref) {
    if (note == null) return ref;
    if (Text.hasControlCharacter(note))
      throw new InvalidOrderException("note", "no control characters");
    if (!Text.isWellFormed(note)) throw new InvalidOrderException("note", "well-formed Unicode");
    String trimmed = Text.trimSpaces(note);
    String label = trimmed.isEmpty() ? ref : trimmed;
    if (Text.codePointLength(label) > Constants.MAX_NOTE_LENGTH) {
      throw new InvalidOrderException("note", Constants.MAX_NOTE_LENGTH + " characters at most");
    }
    return label;
  }

  /**
   * A stored invoice, checked: what its payment check asks.
   *
   * @param ref its order's reference, with an address (null without one)
   * @param lightning its Lightning invoice, null for an order paid on-chain only
   * @param onchainAddress its deposit address, or null
   * @param sats its amount
   * @param expiresAt its end
   */
  public record StoredInvoice(
      String ref,
      Lnurl.LightningInvoice lightning,
      String onchainAddress,
      long sats,
      long expiresAt) {}

  /**
   * A stored invoice, checked (SPEC.md §22): its sats and end; a mainnet invoice of exactly its
   * sats, its own payment hash and end, an https verify URL, or none of the three; a deposit
   * address, or none; at least one of the two. With an address, its reference.
   *
   * @param invoice the invoice
   * @return it, checked
   * @throws InvalidOrderException ({@code invoice}) when it is not one
   */
  public static StoredInvoice checkStoredInvoice(InvoiceToCheck invoice) {
    if (invoice == null) throw new InvalidOrderException("invoice", Model.NOT_STORED);
    String satsText = invoice.sats();
    long expiresAt = invoice.expiresAt();
    if (satsText == null
        || !STORED_SATS.matcher(satsText).matches()
        || Math.abs(expiresAt) > Constants.MAX_MINOR_UNITS) {
      throw new InvalidOrderException("invoice", Model.NOT_STORED);
    }
    long sats = Long.parseLong(satsText);
    if (sats < 1 || sats > Constants.MAX_SATS) {
      throw new InvalidOrderException("invoice", Model.NOT_STORED);
    }
    String bolt11 = invoice.bolt11();
    String paymentHash = invoice.paymentHash();
    String verifyUrl = invoice.verifyUrl();
    Lnurl.LightningInvoice lightning = null;
    if (bolt11 == null) {
      if (paymentHash != null || verifyUrl != null) {
        throw new InvalidOrderException("invoice", Model.NOT_STORED);
      }
    } else {
      Bolt11 decoded = Bolt11.decode(bolt11);
      if (decoded == null
          || !decoded.network().equals("bc")
          || decoded.amountMsat() == null
          || decoded.amountMsat() != sats * 1000
          || !Text.isHex32(paymentHash)
          || !decoded.paymentHash().equals(paymentHash)
          || decoded.expiresAt() != expiresAt
          || Urls.parseHttpsUrl(verifyUrl) == null) {
        throw new InvalidOrderException("invoice", Model.NOT_STORED);
      }
      lightning = new Lnurl.LightningInvoice(bolt11, paymentHash, verifyUrl, expiresAt);
    }
    String address = invoice.onchainAddress();
    if (address != null && !Addresses.isDepositAddress(address)) {
      throw new InvalidOrderException("invoice", Model.NOT_STORED);
    }
    if (lightning == null && address == null) {
      throw new InvalidOrderException("invoice", Model.NOT_STORED);
    }
    String ref = null;
    if (address != null) {
      if (invoice.ref() == null || !REF.matcher(invoice.ref()).matches()) {
        throw new InvalidOrderException(
            "invoice", Model.NOT_STORED + ": with an address, its ref (" + REF_RULE + ")");
      }
      ref = invoice.ref();
    }
    return new StoredInvoice(ref, lightning, address, sats, expiresAt);
  }

  /**
   * A whole number of milliseconds within {@code [min, max]}.
   *
   * @param value the value, or null for the default
   * @param fallback the default
   * @param min the least
   * @param max the most
   * @param name the option's name
   * @return the value
   * @throws InvalidOptionException naming the option otherwise
   */
  public static long checkMilliseconds(Long value, long fallback, long min, long max, String name) {
    if (value == null) return fallback;
    if (value < min || value > max) {
      throw new InvalidOptionException(
          name, "a whole number of milliseconds, " + min + " to " + max);
    }
    return value;
  }
}
