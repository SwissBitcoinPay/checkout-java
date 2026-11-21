package me.sattle.checkout.internal.onchain;

import java.math.BigInteger;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.lightning.Bech32;

/**
 * Deposit addresses (SPEC.md §31, "Deposit addresses", "Payment URIs"): the taproot addresses the
 * merchant's wallet hands out, in their one spelling, and what the shop's page shows of an invoice
 * as one QR code.
 */
public final class Addresses {
  private Addresses() {}

  /** {@code bc1p} and 58 more characters. */
  private static final int DEPOSIT_ADDRESS_LENGTH = 62;

  /** The witness version (1) and the 32-byte program, as 5-bit words. */
  private static final int PROGRAM_WORDS = 53;

  private static final Pattern LOWER_BECH32 = Pattern.compile("bc1[02-9ac-hj-np-z]+");

  /**
   * Whether {@code value} is a deposit address: the bech32m encoding (BIP-350) of the
   * human-readable part {@code bc}, witness version 1 and a 32-byte program (BIP-341), in
   * lowercase.
   *
   * @param value a text, or null
   * @return true when it is one
   */
  public static boolean isDepositAddress(String value) {
    if (value == null
        || value.length() != DEPOSIT_ADDRESS_LENGTH
        || !LOWER_BECH32.matcher(value).matches()) {
      return false;
    }
    Bech32.Decoded decoded = Bech32.decode(value, Bech32.BECH32M);
    if (decoded == null || !decoded.hrp().equals("bc")) return false;
    int[] words = decoded.words();
    // 52 words after the version: 256 bits of program and 4 bits of padding, which are zeros.
    return words.length == PROGRAM_WORDS
        && words[0] == 1
        && (words[words.length - 1] & 0b1111) == 0;
  }

  /**
   * {@code sats} in bitcoins, exact, as BIP-21's {@code amount}: no trailing zero in the fraction,
   * no fraction when whole (49 000 sats is {@code 0.00049}, 100 000 000 sats is {@code 1}).
   *
   * @param sats the amount
   * @return its writing in bitcoins
   */
  public static String btcAmount(long sats) {
    long whole = sats / Constants.SATS_PER_BTC;
    StringBuilder fraction = new StringBuilder(Long.toString(sats % Constants.SATS_PER_BTC));
    while (fraction.length() < 8) fraction.insert(0, '0');
    int end = fraction.length();
    while (end > 0 && fraction.charAt(end - 1) == '0') end -= 1;
    return end == 0 ? Long.toString(whole) : whole + "." + fraction.substring(0, end);
  }

  /**
   * What the shop's page shows as one QR code: {@code bitcoin:<address>?amount=<BTC>}, with {@code
   * &lightning=<invoice>} when there is one, or {@code lightning:<invoice>} without an address.
   *
   * @param address the deposit address, or null
   * @param sats the amount
   * @param bolt11 the Lightning invoice, or null
   * @return the payment URI
   */
  public static String paymentUri(String address, long sats, String bolt11) {
    if (address == null) return "lightning:" + bolt11;
    String uri = "bitcoin:" + address + "?amount=" + btcAmount(sats);
    return bolt11 == null ? uri : uri + "&lightning=" + bolt11;
  }

  /**
   * {@link #paymentUri(String, long, String)} of an amount given as a big integer.
   *
   * @param address the deposit address, or null
   * @param sats the amount
   * @param bolt11 the Lightning invoice, or null
   * @return the payment URI
   */
  public static String paymentUri(String address, BigInteger sats, String bolt11) {
    return paymentUri(address, sats.longValueExact(), bolt11);
  }
}
