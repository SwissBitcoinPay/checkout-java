package me.sattle.checkout;

import me.sattle.checkout.internal.Model;

/**
 * What a payment check needs of an invoice (SPEC.md §3): an {@link Invoice} or a {@link
 * DeclaredInvoice} is one, and so is what {@link #fromJson} reads of the JSON stored with an order.
 * With an address, its order's {@link #ref()} is needed too: the witnesses of its payment are its
 * order's. Nothing is checked here: {@link SattlemeClient#getStatus} checks it before any request
 * ({@link InvalidOrderException}, {@code invoice}).
 */
public interface InvoiceToCheck {
  /**
   * The order's reference: needed with an address.
   *
   * @return the reference, or null
   */
  String ref();

  /**
   * The BOLT11 invoice.
   *
   * @return the invoice, or null for an order paid on-chain only
   */
  String bolt11();

  /**
   * Its payment hash.
   *
   * @return hex32, or null without {@code bolt11}
   */
  String paymentHash();

  /**
   * Its LUD-21 verify URL.
   *
   * @return an https URL, or null without {@code bolt11}
   */
  String verifyUrl();

  /**
   * The deposit address the order leased.
   *
   * @return the address, or null without one
   */
  String onchainAddress();

  /**
   * The amount, in sats.
   *
   * @return a string of digits
   */
  String sats();

  /**
   * When it can no longer be paid.
   *
   * @return Unix seconds
   */
  long expiresAt();

  /**
   * The fields a payment check needs, as given.
   *
   * @param ref the order's reference (needed with an address), or null
   * @param bolt11 the BOLT11 invoice, or null
   * @param paymentHash its payment hash, or null
   * @param verifyUrl its verify URL, or null
   * @param onchainAddress the deposit address, or null
   * @param sats the amount in sats, a string of digits
   * @param expiresAt its end, Unix seconds
   * @return the invoice to check
   */
  static InvoiceToCheck of(
      String ref,
      String bolt11,
      String paymentHash,
      String verifyUrl,
      String onchainAddress,
      String sats,
      long expiresAt) {
    return Model.invoiceToCheck(
        ref, bolt11, paymentHash, verifyUrl, onchainAddress, sats, expiresAt);
  }

  /**
   * What a payment check needs of an invoice stored as JSON (an Invoice's or a DeclaredInvoice's,
   * by any implementation): {@code bolt11}, {@code paymentHash} and {@code verifyUrl} (strings or
   * null), {@code onchainAddress} (a string, null or absent), {@code sats} (a string), {@code
   * expiresAt} (an integer) and, with an address, {@code ref}. Other fields are ignored.
   *
   * @param json the stored JSON
   * @return the invoice to check
   * @throws InvalidOrderException ({@code invoice}) when it is not such JSON
   */
  static InvoiceToCheck fromJson(String json) {
    return Model.invoiceToCheckFromJson(json);
  }
}
