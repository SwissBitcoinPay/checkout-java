package me.sattle.checkout.internal.lightning;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Hex;

/**
 * The fields of a BOLT11 invoice the package checks (SPEC.md §19): network, amount, payment hash,
 * description hash, date and expiry. The signature is not verified: the payee is the LNURL server's
 * node, which nothing here can check; the description hash ties the invoice to the merchant. The
 * first {@code p} and {@code h} fields of 52 words count, and the last {@code x}; an {@code x} of
 * more than 7 words, or an amount above 21 million bitcoins, makes the invoice invalid.
 *
 * @param network {@code bc} mainnet, {@code tb} testnet, {@code tbs} signet, {@code bcrt} regtest
 * @param amountMsat its exact amount in millisatoshis, or null without one
 * @param timestamp Unix seconds
 * @param expirySeconds seconds after {@code timestamp} (3600 when it does not say)
 * @param paymentHash hex, 32 bytes
 * @param descriptionHash hex, 32 bytes; null without one
 */
public record Bolt11(
    String network,
    Long amountMsat,
    long timestamp,
    long expirySeconds,
    String paymentHash,
    String descriptionHash) {

  /** ln, the network, an optional amount: digits without a leading zero, a multiplier. */
  private static final Pattern HRP = Pattern.compile("ln(bcrt|bc|tbs|tb)([0-9]+)?([munp])?");

  private static final int TIMESTAMP_WORDS = 7;
  private static final int SIGNATURE_WORDS = 104;
  private static final int TAG_PAYMENT_HASH = 1;
  private static final int TAG_DESCRIPTION_HASH = 23;
  private static final int TAG_EXPIRY = 6;
  private static final int HASH_WORDS = 52;
  private static final long DEFAULT_EXPIRY_SECONDS = 3600;

  /**
   * Decodes a BOLT11 invoice.
   *
   * @param input the invoice
   * @return its fields, or null when it is not valid
   */
  public static Bolt11 decode(String input) {
    Bech32.Decoded decoded = Bech32.decode(input, Bech32.BECH32);
    if (decoded == null) return null;
    Matcher hrp = HRP.matcher(decoded.hrp());
    if (!hrp.matches()) return null;
    Long amount;
    try {
      amount = amountMsat(hrp.group(2), hrp.group(3));
    } catch (IllegalArgumentException e) {
      return null;
    }
    int[] words = decoded.words();
    if (words.length < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null;
    long timestamp = Bech32.wordsToInteger(Arrays.copyOfRange(words, 0, TIMESTAMP_WORDS));
    int fieldsEnd = words.length - SIGNATURE_WORDS;
    String paymentHash = null;
    String descriptionHash = null;
    Long expirySeconds = null;
    int i = TIMESTAMP_WORDS;
    while (i < fieldsEnd) {
      if (i + 3 > fieldsEnd) return null;
      int tag = words[i];
      int length = words[i + 1] * 32 + words[i + 2];
      if (i + 3 + length > fieldsEnd) return null;
      int[] data = Arrays.copyOfRange(words, i + 3, i + 3 + length);
      // Fields of an unexpected length are skipped; the first p and h count, and the last x.
      if (tag == TAG_PAYMENT_HASH && length == HASH_WORDS && paymentHash == null) {
        paymentHash = Hex.encode(Bech32.wordsToBytes(data));
      } else if (tag == TAG_DESCRIPTION_HASH && length == HASH_WORDS && descriptionHash == null) {
        descriptionHash = Hex.encode(Bech32.wordsToBytes(data));
      } else if (tag == TAG_EXPIRY) {
        if (length > Constants.MAX_BOLT11_EXPIRY_WORDS) return null;
        expirySeconds = Bech32.wordsToInteger(data);
      }
      i += 3 + length;
    }
    if (paymentHash == null) return null;
    return new Bolt11(
        hrp.group(1),
        amount,
        timestamp,
        expirySeconds == null ? DEFAULT_EXPIRY_SECONDS : expirySeconds,
        paymentHash,
        descriptionHash);
  }

  /** The amount in millisatoshis: no multiplier × 10^11, m × 10^8, u × 10^5, n × 10^2, p ÷ 10. */
  private static Long amountMsat(String digits, String multiplier) {
    if (digits == null) {
      if (multiplier == null) return null;
      throw new IllegalArgumentException("A multiplier alone");
    }
    if (digits.length() > 1 && digits.startsWith("0"))
      throw new IllegalArgumentException("Leading zero");
    // Longer than any amount up to 21 million bitcoins, whatever the multiplier.
    if (digits.length() > 22) throw new IllegalArgumentException("Too large");
    BigInteger value = new BigInteger(digits);
    BigInteger msat;
    if ("p".equals(multiplier)) {
      // A pico-bitcoin is a tenth of a millisatoshi: only whole millisatoshis are valid.
      if (value.mod(BigInteger.TEN).signum() != 0) throw new IllegalArgumentException("Not whole");
      msat = value.divide(BigInteger.TEN);
    } else {
      long unit =
          multiplier == null
              ? 100_000_000_000L
              : multiplier.equals("m") ? 100_000_000L : multiplier.equals("u") ? 100_000L : 100L;
      msat = value.multiply(BigInteger.valueOf(unit));
    }
    if (msat.compareTo(BigInteger.valueOf(Constants.MAX_BOLT11_AMOUNT_MSAT)) > 0) {
      throw new IllegalArgumentException("Above 21 million bitcoins");
    }
    return msat.longValueExact();
  }

  /**
   * Its amount in whole sats, when it has one of a whole number of sats above 0.
   *
   * @return the sats, or null
   */
  public Long wholeSats() {
    if (amountMsat == null || amountMsat <= 0 || amountMsat % 1000 != 0) return null;
    return amountMsat / 1000;
  }

  /**
   * When it can no longer be paid: its timestamp + its expiry.
   *
   * @return Unix seconds
   */
  public long expiresAt() {
    return timestamp + expirySeconds;
  }
}
