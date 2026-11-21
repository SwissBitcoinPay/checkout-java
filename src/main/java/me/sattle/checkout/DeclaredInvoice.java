package me.sattle.checkout;

import me.sattle.checkout.internal.Model;

/**
 * An invoice declared for an order, as the merchant's registry holds it ({@link
 * SattlemeClient#findInvoices}, {@link InvoicePendingException}, {@link OrderPaidException};
 * SPEC.md §3): the fields of an {@link Invoice} but {@code rate}, {@code checkoutUrl} and {@code
 * redirectUrl}, which only the shop keeps, and {@link #declaredAt()}.
 *
 * @param ref the order's reference
 * @param bolt11 the BOLT11 invoice; null for an order paid on-chain only
 * @param paymentHash its payment hash (hex32); null without {@code bolt11}
 * @param verifyUrl its LUD-21 verify URL; null without {@code bolt11}
 * @param onchainAddress the deposit address the order leased; null without one
 * @param paymentUri what to show as one QR code
 * @param sats the amount in sats (a string of digits)
 * @param fiat the order's price when it was given in a currency, else null
 * @param note what the merchant's app shows: the note, else the reference
 * @param createdAt Unix seconds: the invoice's date
 * @param expiresAt Unix seconds: when it can no longer be paid
 * @param declaredAt Unix seconds: when it was declared (without {@code bolt11}, when its address
 *     was leased)
 */
public record DeclaredInvoice(
    String ref,
    String bolt11,
    String paymentHash,
    String verifyUrl,
    String onchainAddress,
    String paymentUri,
    String sats,
    Fiat fiat,
    String note,
    long createdAt,
    long expiresAt,
    long declaredAt)
    implements InvoiceToCheck, java.io.Serializable {

  /**
   * The JSON of SPEC.md §3.
   *
   * @return its JSON text
   */
  public String toJson() {
    return Model.write(Model.declaredInvoice(this));
  }

  /**
   * A DeclaredInvoice stored as JSON. Only the types of its fields are checked here.
   *
   * @param json the stored JSON
   * @return the invoice
   * @throws InvalidOrderException ({@code invoice}) when it is not a DeclaredInvoice's JSON
   */
  public static DeclaredInvoice fromJson(String json) {
    return Model.declaredInvoiceFromJson(json);
  }
}
