package me.sattle.checkout;

import me.sattle.checkout.internal.Model;

/**
 * The invoice of an order ({@link SattlemeClient#createInvoice}; SPEC.md §3): store it with the
 * order ({@link #toJson()}, and {@link #fromJson} to read it back). The shop's page needs {@link
 * #paymentUri()} (or {@link #bolt11()} and {@link #onchainAddress()}) and {@link #expiresAt()}
 * only; with a checkout link, the shop sends its customer to {@link #checkoutUrl()} instead. At
 * least one of {@code bolt11} and {@code onchainAddress} is not null.
 *
 * @param ref the order's reference
 * @param bolt11 the BOLT11 invoice, as the Lightning address wrote it; null for an order paid
 *     on-chain only
 * @param paymentHash its payment hash (hex32); null without {@code bolt11}
 * @param verifyUrl its LUD-21 verify URL (https); null without {@code bolt11}
 * @param onchainAddress the deposit address the order leased; null without one
 * @param paymentUri what to show as one QR code: {@code bitcoin:…} or {@code lightning:…}
 * @param sats the amount in sats (a string of digits): the invoice's, and what the address must
 *     receive
 * @param fiat the order's price when given in a currency, else null
 * @param rate the rate {@code fiat} was converted at, else null
 * @param note what the merchant's app shows: the note, else the reference
 * @param createdAt Unix seconds: its BOLT11 timestamp; without {@code bolt11}, when the address was
 *     leased
 * @param expiresAt Unix seconds: when it can no longer be paid
 * @param checkoutUrl its checkout link, null without one
 * @param redirectUrl where its checkout link sends the customer once it is paid, else null
 */
public record Invoice(
    String ref,
    String bolt11,
    String paymentHash,
    String verifyUrl,
    String onchainAddress,
    String paymentUri,
    String sats,
    Fiat fiat,
    RateInfo rate,
    String note,
    long createdAt,
    long expiresAt,
    String checkoutUrl,
    String redirectUrl)
    implements InvoiceToCheck, java.io.Serializable {

  /**
   * The JSON of SPEC.md §3, which every implementation reads.
   *
   * @return its JSON text
   */
  public String toJson() {
    return Model.write(Model.invoice(this));
  }

  /**
   * An Invoice stored as JSON (SPEC.md §3), as any implementation wrote it. Only the types of its
   * fields are checked here; {@link SattlemeClient#getStatus} checks the rest.
   *
   * @param json the stored JSON
   * @return the invoice
   * @throws InvalidOrderException ({@code invoice}) when it is not an Invoice's JSON
   */
  public static Invoice fromJson(String json) {
    return Model.invoiceFromJson(json);
  }
}
