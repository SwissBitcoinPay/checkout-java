package me.sattle.checkout.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.sattle.checkout.InvalidInvoiceException;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;

/**
 * The merchant's Lightning address (LUD-16, LUD-06, LUD-12, LUD-21; SPEC.md §16, §17, §18, §22):
 * plain HTTPS requests to the merchant's LNURL server (breez.tips), which makes invoices for the
 * merchant's wallet even while it is offline, and tells whether one was paid.
 */
public final class Lnurl {
  private Lnurl() {}

  /** An LNURL answer: its object, or the service's refusal (its reason, never null). */
  private record LnurlAnswer(JsonObject json, String refusal) {}

  /**
   * An LNURL answer, classified (SPEC.md §16): a 2xx, or a 4xx but 408 and 429, whose body is an
   * object with {@code status} {@code ERROR} (any ASCII case) is a refusal; anything else that is
   * not a 2xx with a JSON object is no answer.
   */
  private static CompletableFuture<LnurlAnswer> get(Http http, String url) {
    return Tasks.map(
        http.get(url, Map.of()),
        answer -> {
          JsonObject body = Json.object(answer.json());
          int status = answer.status();
          boolean refusalStatus = status >= 400 && status < 500 && status != 408 && status != 429;
          if ((answer.ok() || refusalStatus) && body != null) {
            String serviceStatus = Json.string(body.get("status"));
            if (serviceStatus != null && Text.asciiLower(serviceStatus).equals("error")) {
              String reason = Json.string(body.get("reason"));
              return new LnurlAnswer(null, reason == null ? "" : reason);
            }
          }
          if (!answer.ok() || body == null) {
            throw new NetworkException(
                Urls.hostOf(url) + " answered HTTP " + status + " without an LNURL answer");
          }
          return new LnurlAnswer(body, null);
        });
  }

  /**
   * The LNURL-pay parameters of the merchant's Lightning address.
   *
   * @param callback an https URL
   * @param minSendableMsat the least an invoice may be, in millisatoshis
   * @param maxSendableMsat the most
   * @param metadata the raw metadata: the invoices' description hash is its SHA-256
   * @param commentAllowed the longest comment accepted (LUD-12), 0 when none
   */
  public record PayRequest(
      String callback,
      long minSendableMsat,
      long maxSendableMsat,
      String metadata,
      long commentAllowed) {
    /**
     * Whole sats it accepts: its millisatoshi bounds rounded inwards.
     *
     * @return the least sats
     */
    public long minSats() {
      return (minSendableMsat + 999) / 1000;
    }

    /**
     * The most sats it accepts.
     *
     * @return the most sats
     */
    public long maxSats() {
      return maxSendableMsat / 1000;
    }

    /**
     * The description hash of this address's invoices (SPEC.md §16): SHA-256 of the UTF-8 of {@code
     * metadata} (checked well-formed), hex.
     *
     * @return the hex hash
     */
    public String descriptionHash() {
      return Hex.encode(Hashes.sha256(Text.utf8(metadata)));
    }

    /**
     * Whether {@code sats} is within its sendable sats.
     *
     * @param sats an amount
     * @return true when within
     */
    public boolean sendable(long sats) {
      return sats >= minSats() && sats <= maxSats();
    }
  }

  /** Millisatoshis of a JSON number: a non-negative safe integer, else null. */
  private static Long msat(JsonValue value) {
    Long msat = Json.safeInteger(value);
    return msat != null && msat >= 0 ? msat : null;
  }

  /**
   * Reads the LNURL-pay parameters of a Lightning address (LUD-16): a refusal is {@code network};
   * an object that is not a pay request is {@code invalid_invoice}, a metadata that holds a lone
   * surrogate included (its description hash is SHA-256 of its UTF-8, which it has not).
   *
   * @param http the HTTP client
   * @param address the merchant's Lightning address (checked)
   * @return the pay request
   */
  public static CompletableFuture<PayRequest> fetchPayRequest(Http http, String address) {
    String url = Urls.lightningAddressUrl(address);
    if (url == null) {
      return CompletableFuture.failedFuture(
          new InvalidInvoiceException("The merchant's Lightning address is invalid"));
    }
    return Tasks.map(
        get(http, url),
        answer -> {
          if (answer.refusal() != null) {
            throw new NetworkException(
                Urls.hostOf(url)
                    + " refused the Lightning address: "
                    + Text.excerpt(answer.refusal()));
          }
          JsonObject json = answer.json();
          String callback = Json.string(json.get("callback"));
          Long min = msat(json.get("minSendable"));
          Long max = msat(json.get("maxSendable"));
          String metadata = Json.string(json.get("metadata"));
          if (!"payRequest".equals(Json.string(json.get("tag")))
              || callback == null
              || Urls.parseHttpsUrl(callback) == null
              || min == null
              || max == null
              || min > max
              // A lone surrogate (an escape such as \ud800) is no text: no UTF-8 to hash
              // (SPEC.md §6, §16), never U+FFFD hashed in its place.
              || metadata == null
              || !Text.isWellFormed(metadata)) {
            throw new InvalidInvoiceException(
                Urls.hostOf(url) + " gave no valid LNURL-pay request");
          }
          Long comment = Json.safeInteger(json.get("commentAllowed"));
          return new PayRequest(
              callback, min, max, metadata, comment == null ? 0 : Math.max(0, comment));
        });
  }

  /**
   * What the Lightning address answered: an invoice and its LUD-21 verify URL, not checked yet.
   *
   * @param pr the BOLT11 invoice
   * @param verify its verify URL
   */
  public record InvoiceAnswer(String pr, String verify) {}

  /**
   * The callback's URL of an invoice of {@code sats} (SPEC.md §17, step 5): its own query
   * parameters kept, its fragment (from its first {@code #}) dropped, {@code amount} = sats × 1000,
   * {@code expiry} and {@code comment} set, written as a form (WHATWG's {@code URLSearchParams}).
   *
   * @param pay the pay request
   * @param sats the amount
   * @param expirySeconds the expiry asked
   * @param comment the label, sent only when its UTF-8 fits {@code commentAllowed} bytes
   * @return the URL
   */
  public static String invoiceUrl(PayRequest pay, long sats, long expirySeconds, String comment) {
    String url = pay.callback();
    int hash = url.indexOf('#');
    if (hash >= 0) url = url.substring(0, hash);
    int question = url.indexOf('?');
    String base = question < 0 ? url : url.substring(0, question);
    List<String[]> params = new ArrayList<>();
    if (question >= 0) {
      for (String pair : url.substring(question + 1).split("&", -1)) {
        if (pair.isEmpty()) continue;
        int equals = pair.indexOf('=');
        String name = equals < 0 ? pair : pair.substring(0, equals);
        String value = equals < 0 ? "" : pair.substring(equals + 1);
        params.add(new String[] {formDecode(name), formDecode(value)});
      }
    }
    set(params, "amount", Long.toString(Math.multiplyExact(sats, 1000L)));
    set(params, "expiry", Long.toString(expirySeconds));
    if (comment != null && !comment.isEmpty() && Text.utf8Length(comment) <= pay.commentAllowed()) {
      set(params, "comment", comment);
    }
    StringBuilder out = new StringBuilder(base);
    String separator = "?";
    for (String[] param : params) {
      out.append(separator).append(formEncode(param[0])).append('=').append(formEncode(param[1]));
      separator = "&";
    }
    return out.toString();
  }

  /** {@code URLSearchParams.set}: the first pair of the name gets the value, the others go. */
  private static void set(List<String[]> params, String name, String value) {
    boolean found = false;
    for (int i = 0; i < params.size(); ) {
      if (params.get(i)[0].equals(name)) {
        if (found) {
          params.remove(i);
          continue;
        }
        params.get(i)[1] = value;
        found = true;
      }
      i += 1;
    }
    if (!found) params.add(new String[] {name, value});
  }

  /**
   * application/x-www-form-urlencoded decoding (WHATWG): {@code +} a space, {@code %} and two hex
   * digits a byte, the bytes read as UTF-8 (a callback is an https URL: ASCII only).
   */
  private static String formDecode(String text) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < text.length(); i += 1) {
      char c = text.charAt(i);
      if (c == '+') {
        bytes.write(' ');
      } else if (c == '%'
          && i + 2 < text.length()
          && isAsciiHexDigit(text.charAt(i + 1))
          && isAsciiHexDigit(text.charAt(i + 2))) {
        bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        byte[] raw = Text.utf8(String.valueOf(c));
        bytes.write(raw, 0, raw.length);
      }
    }
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  private static boolean isAsciiHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /** application/x-www-form-urlencoded encoding, as WHATWG writes it. */
  private static String formEncode(String text) {
    StringBuilder out = new StringBuilder();
    for (byte b : Text.utf8(text)) {
      int c = b & 0xff;
      boolean plain =
          c == '*'
              || c == '-'
              || c == '.'
              || c == '_'
              || (c >= '0' && c <= '9')
              || (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z');
      if (plain) out.append((char) c);
      else if (c == ' ') out.append('+');
      else out.append('%').append(String.format("%02X", c));
    }
    return out.toString();
  }

  /**
   * Asks the Lightning address for an invoice of {@code sats} (LUD-06): a refusal is {@code
   * network}; an answer without {@code pr} and {@code verify} strings is {@code invalid_invoice}.
   * The answer is not checked: {@link #checkInvoice} does.
   *
   * @param http the HTTP client
   * @param pay the pay request
   * @param sats the amount
   * @param expirySeconds the expiry asked
   * @param comment the label
   * @return the answer
   */
  public static CompletableFuture<InvoiceAnswer> requestInvoice(
      Http http, PayRequest pay, long sats, long expirySeconds, String comment) {
    return Tasks.map(
        get(http, invoiceUrl(pay, sats, expirySeconds, comment)),
        answer -> {
          if (answer.refusal() != null) {
            throw new NetworkException(
                "The Lightning address refused the invoice: " + Text.excerpt(answer.refusal()));
          }
          String pr = Json.string(answer.json().get("pr"));
          String verify = Json.string(answer.json().get("verify"));
          if (pr == null || verify == null) {
            throw new InvalidInvoiceException(
                "The Lightning address gave no invoice or no verify URL (LUD-21)");
          }
          return new InvoiceAnswer(pr, verify);
        });
  }

  /**
   * The first check of SPEC.md §18 an invoice fails, in their order.
   *
   * @param answer the Lightning address's answer
   * @param pay the pay request
   * @param sats the order's amount
   * @param durationSeconds the expiry asked
   * @param now the server's clock, Unix seconds
   * @return the check's name ({@code bolt11}, {@code network}, {@code amount}, {@code
   *     description_hash}, {@code verify_url}, {@code duration}, {@code expired}, {@code clock}),
   *     or null when it passes them all
   */
  public static String failedCheck(
      InvoiceAnswer answer, PayRequest pay, long sats, long durationSeconds, long now) {
    Bolt11 decoded = Bolt11.decode(answer.pr());
    if (decoded == null) return "bolt11";
    if (!decoded.network().equals("bc")) return "network";
    if (decoded.amountMsat() == null || decoded.amountMsat() != sats * 1000) return "amount";
    if (!pay.descriptionHash().equals(decoded.descriptionHash())) return "description_hash";
    Urls.HttpsUrl verify = Urls.parseHttpsUrl(answer.verify());
    if (verify == null || !verify.sameHost(Urls.parseHttpsUrl(pay.callback()))) return "verify_url";
    if (decoded.expirySeconds() > durationSeconds) return "duration";
    if (decoded.expirySeconds() <= 0) return "expired";
    if (Math.abs(decoded.timestamp() - now) > Constants.CLOCK_TOLERANCE_SECONDS) return "clock";
    // Dated up to 5 minutes back, a short invoice may already be over: never declared nor shown.
    if (decoded.timestamp() + decoded.expirySeconds() <= now) return "expired";
    return null;
  }

  /**
   * Checks an invoice before it is declared or shown (SPEC.md §18).
   *
   * @param answer the Lightning address's answer
   * @param pay the pay request
   * @param sats the order's amount
   * @param durationSeconds the expiry asked
   * @param now the server's clock, Unix seconds
   * @return the decoded invoice
   * @throws InvalidInvoiceException naming the first check that fails
   */
  public static Bolt11 checkInvoice(
      InvoiceAnswer answer, PayRequest pay, long sats, long durationSeconds, long now) {
    String failed = failedCheck(answer, pay, sats, durationSeconds, now);
    if (failed != null) {
      String message =
          switch (failed) {
            case "bolt11" -> "Not a BOLT11 invoice";
            case "network" -> "Not a Bitcoin mainnet invoice";
            case "amount" -> "The invoice's amount differs from the order's";
            case "description_hash" -> "The invoice does not pay the merchant's Lightning address";
            case "verify_url" -> "No verify URL (LUD-21) on the Lightning address's host";
            case "duration" -> "The invoice lasts longer than asked";
            case "clock" -> "The invoice's date is too far from this server's clock";
            default -> "The invoice has no time left";
          };
      throw new InvalidInvoiceException(message);
    }
    return Bolt11.decode(answer.pr());
  }

  /**
   * What the Lightning part of a payment check needs of an invoice.
   *
   * @param bolt11 the invoice
   * @param paymentHash its payment hash
   * @param verifyUrl its verify URL
   * @param expiresAt its end
   */
  public record LightningInvoice(
      String bolt11, String paymentHash, String verifyUrl, long expiresAt) {}

  /**
   * The Lightning part of an invoice's payment (LUD-21; SPEC.md §22), checked: the verify answer
   * names this very invoice (whatever the case of its ASCII letters), {@code settled} is true and
   * the preimage's SHA-256 is the payment hash. Past the invoice's end and its grace, "not settled"
   * and "Not found" are final ({@code expired}); before, any refusal keeps it {@code open}.
   *
   * @param http the HTTP client
   * @param invoice the invoice
   * @param now the server's clock, Unix seconds
   * @return its Lightning part ({@code network} for no answer, or another refusal past the grace;
   *     {@code invalid_invoice} for an answer about another invoice, or a wrong preimage)
   */
  public static CompletableFuture<LightningPart> verifyPayment(
      Http http, LightningInvoice invoice, long now) {
    boolean isFinal = now >= invoice.expiresAt() + Constants.GRACE_SECONDS;
    return Tasks.map(
        get(http, invoice.verifyUrl()),
        answer -> {
          if (answer.refusal() != null) {
            if (!isFinal) return LightningPart.OPEN;
            if (answer.refusal().equals(Constants.NOT_FOUND_REASON)) return LightningPart.EXPIRED;
            throw new NetworkException(
                "The payment check was refused: " + Text.excerpt(answer.refusal()));
          }
          JsonObject json = answer.json();
          String pr = Json.string(json.get("pr"));
          if (pr == null) {
            throw new InvalidInvoiceException("The payment check names no invoice (LUD-21 pr)");
          }
          // BOLT11 is case-insensitive (bech32): the same invoice may be written in upper case.
          if (!Text.asciiLower(pr).equals(Text.asciiLower(invoice.bolt11()))) {
            throw new InvalidInvoiceException("The payment check is about another invoice");
          }
          if (!Json.isTrue(json.get("settled"))) {
            return isFinal ? LightningPart.EXPIRED : LightningPart.OPEN;
          }
          String preimage = Json.string(json.get("preimage"));
          if (preimage == null || !isHex64Any(preimage)) {
            throw new InvalidInvoiceException("Paid without a valid preimage");
          }
          String proof = Text.asciiLower(preimage);
          if (!Hex.encode(Hashes.sha256(Hex.decode(proof))).equals(invoice.paymentHash())) {
            throw new InvalidInvoiceException("The preimage does not match the payment hash");
          }
          return LightningPart.paid(proof);
        });
  }

  /** 64 hex characters of any ASCII case. */
  private static boolean isHex64Any(String value) {
    if (value.length() != 64) return false;
    for (int i = 0; i < 64; i += 1) {
      char c = value.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
        return false;
    }
    return true;
  }

  /**
   * The Lightning part of an invoice: open, expired for good, or paid with its preimage.
   *
   * @param state its state
   * @param preimage the preimage when paid (lowercase hex), else null
   */
  public record LightningPart(State state, String preimage) {
    /** The states of a Lightning part. */
    public enum State {
      /** Not paid (yet). */
      OPEN,
      /** Never paid, for good. */
      EXPIRED,
      /** Paid. */
      PAID
    }

    /** Not paid (yet). */
    public static final LightningPart OPEN = new LightningPart(State.OPEN, null);

    /** Never paid, for good. */
    public static final LightningPart EXPIRED = new LightningPart(State.EXPIRED, null);

    /**
     * Paid.
     *
     * @param preimage its preimage
     * @return the part
     */
    public static LightningPart paid(String preimage) {
      return new LightningPart(State.PAID, preimage);
    }

    /**
     * Whether it is paid.
     *
     * @return true when paid
     */
    public boolean isPaid() {
      return state == State.PAID;
    }

    /**
     * Whether it is final: paid, or expired for good.
     *
     * @return true when final
     */
    public boolean isFinal() {
      return state != State.OPEN;
    }
  }
}
