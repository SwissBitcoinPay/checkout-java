package me.sattle.checkout.internal.crypto;

import fr.acinq.secp256k1.Secp256k1;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Text;

/**
 * secp256k1 through ACINQ's JNI bindings to Bitcoin Core's libsecp256k1 (secp256k1-kmp): BIP-340
 * Schnorr signatures (with 32 auxiliary random bytes) and their verification, x-only public keys
 * and {@code lift_x}, and the shared point NIP-44 needs. Nothing of the curve is written here.
 *
 * <p>NIP-44 needs the x coordinate of the shared point, unhashed: libsecp256k1's {@code
 * secp256k1_ecdh} (secp256k1-kmp's {@code ecdh}) hashes it with SHA-256, so the shared point is a
 * multiplication of the peer's point by the secret ({@code pubKeyTweakMul}) instead.
 */
public final class Curve {
  private Curve() {}

  /** BIP-340 verifications made, for the tests that count them. */
  private static final AtomicLong VERIFICATIONS = new AtomicLong();

  private static final Secp256k1 SECP256K1 = load();

  /**
   * secp256k1-kmp, read by this module: its package is split across two jars, which the module path
   * refuses, so they are on the class path (the unnamed module), which a named module reads only
   * once it says so. Nothing to do on the class path, where every module reads every other.
   */
  private static Secp256k1 load() {
    try {
      Class<?> api =
          Class.forName("fr.acinq.secp256k1.Secp256k1", false, Curve.class.getClassLoader());
      Curve.class.getModule().addReads(api.getModule());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "secp256k1-kmp (fr.acinq.secp256k1:secp256k1-kmp-jvm) is not on the class path", e);
    }
    return Secp256k1.get();
  }

  /**
   * How many BIP-340 verifications were made, in this JVM.
   *
   * @return the count
   */
  public static long verifications() {
    return VERIFICATIONS.get();
  }

  /**
   * Whether {@code secret} is a secret key: 32 bytes, from 1 to the curve's order minus 1.
   *
   * @param secret the bytes
   * @return true when it is one
   */
  public static boolean isSecretKey(byte[] secret) {
    return secret.length == 32 && SECP256K1.secKeyVerify(secret);
  }

  /**
   * The BIP-340 x-only public key of {@code secret}.
   *
   * @param secret a secret key
   * @return its x-only public key, hex
   */
  public static String publicKeyOf(byte[] secret) {
    byte[] point = SECP256K1.pubkeyCreate(secret);
    return Hex.encode(Arrays.copyOfRange(point, 1, 33));
  }

  /**
   * Whether {@code value} is an x-only public key: lowercase hex32, the x coordinate of a point of
   * the curve (BIP-340 {@code lift_x} succeeds).
   *
   * @param value a text, or null
   * @return true when it is one
   */
  public static boolean isPublicKey(String value) {
    if (!Text.isHex32(value)) return false;
    try {
      SECP256K1.pubkeyParse(evenPoint(value));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * A BIP-340 signature of {@code message} (32 bytes) by {@code secret}, with {@code auxiliary}, 32
   * fresh random bytes.
   *
   * @param message the 32 bytes signed (an event's id)
   * @param secret the secret key
   * @param auxiliary 32 random bytes
   * @return the 64-byte signature
   */
  public static byte[] sign(byte[] message, byte[] secret, byte[] auxiliary) {
    if (auxiliary.length != 32) throw new IllegalArgumentException("32 auxiliary bytes");
    return SECP256K1.signSchnorr(message, secret, auxiliary);
  }

  /**
   * Whether {@code signature} is a BIP-340 signature of {@code message} by {@code publicKey}.
   *
   * @param signature 64 bytes
   * @param message 32 bytes
   * @param publicKey the x-only public key, 32 bytes
   * @return true when it verifies; false for anything else
   */
  public static boolean verify(byte[] signature, byte[] message, byte[] publicKey) {
    VERIFICATIONS.incrementAndGet();
    if (signature.length != 64 || message.length != 32 || publicKey.length != 32) return false;
    try {
      return SECP256K1.verifySchnorr(signature, message, publicKey);
    } catch (RuntimeException e) {
      // A public key off the curve.
      return false;
    }
  }

  /**
   * The x coordinate of the shared point of {@code secret} and the x-only {@code publicKey}
   * (NIP-44's {@code get_conversation_key} before its HKDF-Extract): {@code secret} × the point of
   * {@code publicKey} with an even y, unhashed.
   *
   * @param secret a secret key
   * @param publicKey an x-only public key, hex
   * @return the 32-byte x coordinate
   * @throws IllegalArgumentException for a secret out of range or a key off the curve
   */
  public static byte[] sharedX(byte[] secret, String publicKey) {
    if (!isSecretKey(secret)) throw new IllegalArgumentException("Invalid secret key");
    if (!Text.isHex32(publicKey)) throw new IllegalArgumentException("Invalid public key");
    byte[] shared;
    try {
      shared = SECP256K1.pubKeyTweakMul(evenPoint(publicKey), secret);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid public key", e);
    }
    // An uncompressed point: 04, x, y.
    return Arrays.copyOfRange(shared, 1, 33);
  }

  /** The compressed point of an x-only key: its x with an even y (02). */
  private static byte[] evenPoint(String xOnly) {
    byte[] point = new byte[33];
    point[0] = 2;
    System.arraycopy(Hex.decode(xOnly), 0, point, 1, 32);
    return point;
  }
}
