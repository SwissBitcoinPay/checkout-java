package me.sattle.checkout.support;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Hashes;

/**
 * BOLT11 invoices built for tests, so that a test chooses their fields. The signature is 104 zero
 * words: nothing verifies it.
 */
public final class Lightning {
  private Lightning() {}

  private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
  private static final int[] GENERATOR = {
    0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
  };

  static int polymod(List<Integer> values) {
    int chk = 1;
    for (int value : values) {
      int top = chk >>> 25;
      chk = ((chk & 0x1ffffff) << 5) ^ value;
      for (int i = 0; i < 5; i += 1) if (((top >>> i) & 1) != 0) chk ^= GENERATOR[i];
    }
    return chk;
  }

  static List<Integer> expandHrp(String hrp) {
    List<Integer> out = new ArrayList<>();
    for (char c : hrp.toCharArray()) out.add(c >> 5);
    out.add(0);
    for (char c : hrp.toCharArray()) out.add(c & 31);
    return out;
  }

  /**
   * Bech32 of {@code words} under {@code hrp}, with the checksum constant {@code constant}.
   *
   * @param hrp the human-readable part
   * @param words the data, 5-bit words
   * @param constant 1 (bech32) or 0x2bc830a3 (bech32m)
   * @return the encoding
   */
  public static String encode(String hrp, List<Integer> words, int constant) {
    List<Integer> values = new ArrayList<>(expandHrp(hrp));
    values.addAll(words);
    values.addAll(List.of(0, 0, 0, 0, 0, 0));
    int mod = polymod(values) ^ constant;
    StringBuilder out = new StringBuilder(hrp).append('1');
    for (int word : words) out.append(CHARSET.charAt(word));
    for (int i = 0; i < 6; i += 1) out.append(CHARSET.charAt((mod >>> (5 * (5 - i))) & 31));
    return out.toString();
  }

  /**
   * Bytes as 5-bit words, the last padded with zeros.
   *
   * @param bytes the bytes
   * @return the words
   */
  public static List<Integer> bytesToWords(byte[] bytes) {
    List<Integer> words = new ArrayList<>();
    int accumulator = 0;
    int bits = 0;
    for (byte b : bytes) {
      accumulator = (accumulator << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        words.add((accumulator >> bits) & 31);
      }
    }
    if (bits > 0) words.add((accumulator << (5 - bits)) & 31);
    return words;
  }

  /**
   * An integer as 5-bit words, big-endian.
   *
   * @param value the integer
   * @param length the least number of words, or 0
   * @return the words
   */
  public static List<Integer> integerWords(long value, int length) {
    List<Integer> words = new ArrayList<>();
    long rest = value;
    do {
      words.add(0, (int) (rest % 32));
      rest /= 32;
    } while (rest > 0);
    while (words.size() < length) words.add(0, 0);
    return words;
  }

  /**
   * A tagged field.
   *
   * @param tag its tag
   * @param data its words
   * @return the field's words
   */
  public static List<Integer> field(int tag, List<Integer> data) {
    List<Integer> out = new ArrayList<>(List.of(tag, data.size() / 32, data.size() % 32));
    out.addAll(data);
    return out;
  }

  /**
   * A preimage and its payment hash.
   *
   * @param preimage the preimage (hex)
   * @param paymentHash its SHA-256 (hex)
   */
  public record Pair(String preimage, String paymentHash) {}

  /**
   * A 32-byte preimage made of {@code seed}, and its payment hash.
   *
   * @param seed the byte
   * @return the pair
   */
  public static Pair preimagePair(int seed) {
    byte[] preimage = new byte[32];
    Arrays.fill(preimage, (byte) seed);
    return new Pair(Hex.encode(preimage), Hex.encode(Hashes.sha256(preimage)));
  }

  /**
   * The description hash of {@code metadata}.
   *
   * @param metadata the LNURL metadata
   * @return its SHA-256 (hex)
   */
  public static String metadataHash(String metadata) {
    return Hex.encode(Hashes.sha256(Text.utf8(metadata)));
  }

  private static String amountPart(BigInteger msat) {
    BigInteger[] units = {
      BigInteger.valueOf(100_000_000_000L),
      BigInteger.valueOf(100_000_000L),
      BigInteger.valueOf(100_000L),
      BigInteger.valueOf(100L)
    };
    String[] suffixes = {"", "m", "u", "n"};
    for (int i = 0; i < units.length; i += 1) {
      if (msat.mod(units[i]).signum() == 0) return msat.divide(units[i]) + suffixes[i];
    }
    return msat.multiply(BigInteger.TEN) + "p";
  }

  /** What an invoice of a test holds. */
  public static final class Spec {
    /** Its amount, or null without one. */
    public BigInteger amountMsat;

    /** Its network. */
    public String network = "bc";

    /** Its payment hash (hex). */
    public String paymentHash;

    /** Its description hash (hex), or null. */
    public String descriptionHash;

    /** Its timestamp. */
    public long timestamp;

    /** Its expiry, or null without an {@code x} field. */
    public Long expirySeconds;

    /** More tagged fields, after the others: a tag and its words. */
    public List<Object[]> extra = new ArrayList<>();

    /** An empty invoice. */
    public Spec() {}
  }

  /**
   * A BOLT11 invoice of {@code spec}.
   *
   * @param spec what it holds
   * @return the invoice
   */
  @SuppressWarnings("unchecked")
  public static String makeInvoice(Spec spec) {
    String hrp = "ln" + spec.network + (spec.amountMsat == null ? "" : amountPart(spec.amountMsat));
    List<Integer> words = new ArrayList<>(integerWords(spec.timestamp, 7));
    words.addAll(field(1, bytesToWords(Hex.decode(spec.paymentHash))));
    if (spec.descriptionHash != null) {
      words.addAll(field(23, bytesToWords(Hex.decode(spec.descriptionHash))));
    }
    if (spec.expirySeconds != null) words.addAll(field(6, integerWords(spec.expirySeconds, 0)));
    for (Object[] extra : spec.extra)
      words.addAll(field((Integer) extra[0], (List<Integer>) extra[1]));
    for (int i = 0; i < 104; i += 1) words.add(0);
    return encode(hrp, words, 1);
  }

  /**
   * An invoice of {@code msat} for a payment hash, a description hash, dated and lasting so.
   *
   * @param amountMsat the amount
   * @param paymentHash the payment hash
   * @param descriptionHash the description hash
   * @param timestamp the date
   * @param expirySeconds the expiry
   * @return the invoice
   */
  public static String invoice(
      long amountMsat,
      String paymentHash,
      String descriptionHash,
      long timestamp,
      long expirySeconds) {
    Spec spec = new Spec();
    spec.amountMsat = BigInteger.valueOf(amountMsat);
    spec.paymentHash = paymentHash;
    spec.descriptionHash = descriptionHash;
    spec.timestamp = timestamp;
    spec.expirySeconds = expirySeconds;
    return makeInvoice(spec);
  }
}
