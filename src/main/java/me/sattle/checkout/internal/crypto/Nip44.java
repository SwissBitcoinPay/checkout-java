package me.sattle.checkout.internal.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import me.sattle.checkout.internal.Base64Strict;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.json.Json;

/**
 * NIP-44 version 2 (https://github.com/nostr-protocol/nips/blob/master/44.md), as the merchant's
 * app implements it: the JDK's raw IETF ChaCha20 (12-byte nonce, counter 0), HMAC-SHA256 over the
 * nonce and the ciphertext, per-message keys from a random 32-byte nonce through HKDF, the
 * plaintext padded. Checked against the official vectors. A conversation key is a 32-byte HKDF
 * output used as is, except inside a registry declaration and a lease: the ECDH conversation key
 * ({@link #conversationKey}).
 */
public final class Nip44 {
  private Nip44() {}

  private static final int VERSION = 2;
  private static final int MIN_PLAINTEXT = 1;
  private static final int MAX_PLAINTEXT = 0xffff;
  private static final byte[] SALT = "nip44-v2".getBytes(StandardCharsets.UTF_8);

  /**
   * A payload of another NIP-44 version (empty, starting with {@code #}, or another version byte).
   */
  public static final class UnknownVersionException extends Exception {
    private static final long serialVersionUID = 1L;

    /** A payload of an unknown version. */
    public UnknownVersionException() {
      super("NIP-44 payload of an unknown version");
    }
  }

  /** A payload that does not decrypt under the key, or is malformed. */
  public static final class DecryptException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Not decrypted.
     *
     * @param message why
     */
    public DecryptException(String message) {
      super(message);
    }
  }

  /** A plaintext NIP-44 cannot seal: empty, or more than 65 535 bytes of UTF-8. */
  public static final class PlaintextException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Too long or empty. */
    public PlaintextException() {
      super("Invalid plaintext length");
    }
  }

  /** The keys of one message. */
  public record MessageKeys(byte[] chachaKey, byte[] chachaNonce, byte[] hmacKey) {}

  /**
   * The conversation key of two parties ({@code get_conversation_key}): HKDF-Extract, salt {@code
   * nip44-v2}, of the x coordinate of the shared point of {@code secret} and the x-only {@code
   * publicKey}.
   *
   * @param secret a secret key
   * @param publicKey an x-only public key, hex
   * @return the 32-byte conversation key
   * @throws IllegalArgumentException for a secret out of range or a key off the curve
   */
  public static byte[] conversationKey(byte[] secret, String publicKey) {
    return Hashes.hkdfExtract(SALT, Curve.sharedX(secret, publicKey));
  }

  /**
   * The padded length of a plaintext of {@code length} bytes ({@code calc_padded_len}).
   *
   * @param length the bytes, from 1
   * @return the padded length
   */
  public static int paddedLength(int length) {
    if (length <= 32) return 32;
    int nextPower = Integer.highestOneBit(length - 1) << 1;
    int chunk = nextPower <= 256 ? 32 : nextPower / 8;
    return chunk * ((length - 1) / chunk + 1);
  }

  /**
   * The keys of one message ({@code get_message_keys}): HKDF-Expand of the conversation key with
   * the nonce, 76 bytes.
   *
   * @param conversationKey 32 bytes
   * @param nonce 32 bytes
   * @return the ChaCha20 key and nonce and the HMAC key
   */
  public static MessageKeys messageKeys(byte[] conversationKey, byte[] nonce) {
    if (conversationKey.length != 32) throw new IllegalArgumentException("Conversation key length");
    if (nonce.length != 32) throw new IllegalArgumentException("Nonce length");
    byte[] keys = Hashes.hkdfExpand(conversationKey, nonce, 76);
    return new MessageKeys(
        Arrays.copyOfRange(keys, 0, 32),
        Arrays.copyOfRange(keys, 32, 44),
        Arrays.copyOfRange(keys, 44, 76));
  }

  /**
   * Encrypts {@code text} under {@code conversationKey} with {@code nonce} (32 fresh random bytes).
   *
   * @param text the plaintext
   * @param conversationKey 32 bytes
   * @param nonce 32 random bytes
   * @return the payload (padded base64)
   * @throws PlaintextException when the text is empty or more than 65 535 bytes
   */
  public static String encrypt(String text, byte[] conversationKey, byte[] nonce) {
    MessageKeys keys = messageKeys(conversationKey, nonce);
    byte[] ciphertext = chacha20(keys, pad(text));
    byte[] mac = Hashes.hmacSha256(keys.hmacKey(), nonce, ciphertext);
    byte[] payload = new byte[1 + 32 + ciphertext.length + 32];
    payload[0] = VERSION;
    System.arraycopy(nonce, 0, payload, 1, 32);
    System.arraycopy(ciphertext, 0, payload, 33, ciphertext.length);
    System.arraycopy(mac, 0, payload, 33 + ciphertext.length, 32);
    return Base64Strict.encodeStandard(payload);
  }

  /**
   * The text of a NIP-44 v2 payload. Refuses (SPEC.md §12) a payload shorter than 132 or longer
   * than 87 472 characters, one that is not strict padded base64, decoded data shorter than 99 or
   * longer than 65 603 bytes, a wrong MAC (compared in constant time), a wrong padding, a plaintext
   * that is not strict UTF-8 (a byte order mark is a character like any other).
   *
   * @param payload the payload
   * @param conversationKey 32 bytes
   * @return the plaintext
   * @throws UnknownVersionException for a payload of another version
   * @throws DecryptException when it does not decrypt
   */
  public static String decrypt(String payload, byte[] conversationKey)
      throws UnknownVersionException, DecryptException {
    if (payload.isEmpty() || payload.charAt(0) == '#') throw new UnknownVersionException();
    if (payload.length() < 132 || payload.length() > 87_472) {
      throw new DecryptException("Invalid payload size");
    }
    byte[] data = Base64Strict.decodeStandard(payload);
    if (data == null || data.length < 99 || data.length > 65_603) {
      throw new DecryptException("Invalid payload");
    }
    if (data[0] != VERSION) throw new UnknownVersionException();
    byte[] nonce = Arrays.copyOfRange(data, 1, 33);
    byte[] ciphertext = Arrays.copyOfRange(data, 33, data.length - 32);
    MessageKeys keys = messageKeys(conversationKey, nonce);
    byte[] mac = Hashes.hmacSha256(keys.hmacKey(), nonce, ciphertext);
    if (!Hashes.constantTimeEquals(mac, Arrays.copyOfRange(data, data.length - 32, data.length))) {
      throw new DecryptException("Invalid MAC");
    }
    return unpad(chacha20(keys, ciphertext));
  }

  private static byte[] pad(String text) {
    byte[] bytes = Text.utf8(text);
    if (bytes.length < MIN_PLAINTEXT || bytes.length > MAX_PLAINTEXT) {
      throw new PlaintextException();
    }
    byte[] padded = new byte[2 + paddedLength(bytes.length)];
    padded[0] = (byte) (bytes.length >> 8);
    padded[1] = (byte) bytes.length;
    System.arraycopy(bytes, 0, padded, 2, bytes.length);
    return padded;
  }

  private static String unpad(byte[] padded) throws DecryptException {
    if (padded.length < 2) throw new DecryptException("Invalid padding");
    int length = ((padded[0] & 0xff) << 8) | (padded[1] & 0xff);
    if (length < MIN_PLAINTEXT || padded.length != 2 + paddedLength(length)) {
      throw new DecryptException("Invalid padding");
    }
    String text = Json.strictUtf8(Arrays.copyOfRange(padded, 2, 2 + length));
    if (text == null) throw new DecryptException("Invalid UTF-8");
    return text;
  }

  /** Raw IETF ChaCha20 (RFC 8439 §2.4), counter 0: its own inverse. */
  private static byte[] chacha20(MessageKeys keys, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("ChaCha20");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(keys.chachaKey(), "ChaCha20"),
          new ChaCha20ParameterSpec(keys.chachaNonce(), 0));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("ChaCha20 is required of every Java 11+ platform", e);
    }
  }
}
