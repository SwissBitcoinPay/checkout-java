package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;

/**
 * NIP-44 v2, with the official vectors (vectors/nip44/nip44.vectors.json, see its {@code source}),
 * as checkout-node's NIP-44 tests.
 */
class Nip44Test {
  private static final JsonValue VECTORS = Js.spec("vectors/nip44/nip44.vectors.json");
  private static final RandomBytes RANDOM = new RandomBytes(new SecureRandom());

  private static String decrypt(String payload, byte[] key) throws Exception {
    return Nip44.decrypt(payload, key);
  }

  @Test
  void padsPlaintextsToTheSpecsLengths() {
    List<JsonValue> pairs = Js.list(VECTORS, "calc_padded_len");
    assertEquals(23, pairs.size());
    for (JsonValue pair : pairs) {
      List<JsonValue> values = Js.items(pair);
      assertEquals(
          (int) ((JsonNumber) values.get(1)).value(),
          Nip44.paddedLength((int) ((JsonNumber) values.get(0)).value()));
    }
  }

  @Test
  void derivesTheSpecsConversationKeysAndRefusesInvalidOnes() {
    for (JsonValue vector : Js.list(VECTORS, "get_conversation_key")) {
      assertEquals(
          Js.text(vector, "conversation_key"),
          Hex.encode(Nip44.conversationKey(Js.hex(vector, "sec1"), Js.text(vector, "pub2"))));
    }
    for (JsonValue vector : Js.list(VECTORS, "invalid_get_conversation_key")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Nip44.conversationKey(Js.hex(vector, "sec1"), Js.text(vector, "pub2")),
          Js.text(vector, "note"));
    }
  }

  @Test
  void givesBothPartiesTheSameConversationKey() {
    byte[] a = RANDOM.next(32);
    byte[] b = RANDOM.next(32);
    assertArrayEquals(
        Nip44.conversationKey(a, Curve.publicKeyOf(b)),
        Nip44.conversationKey(b, Curve.publicKeyOf(a)));
  }

  @Test
  void derivesTheSpecsMessageKeys() {
    JsonValue messageKeys = Js.get(VECTORS, "get_message_keys");
    byte[] key = Js.hex(messageKeys, "conversation_key");
    for (JsonValue expected : Js.list(messageKeys, "keys")) {
      Nip44.MessageKeys keys = Nip44.messageKeys(key, Js.hex(expected, "nonce"));
      assertEquals(Js.text(expected, "chacha_key"), Hex.encode(keys.chachaKey()));
      assertEquals(Js.text(expected, "chacha_nonce"), Hex.encode(keys.chachaNonce()));
      assertEquals(Js.text(expected, "hmac_key"), Hex.encode(keys.hmacKey()));
    }
  }

  @Test
  void encryptsAndDecryptsTheSpecsPayloads() throws Exception {
    for (JsonValue vector : Js.list(VECTORS, "encrypt_decrypt")) {
      byte[] key = Js.hex(vector, "conversation_key");
      assertEquals(
          Js.text(vector, "payload"),
          Nip44.encrypt(Js.text(vector, "plaintext"), key, Js.hex(vector, "nonce")));
      assertEquals(Js.text(vector, "plaintext"), decrypt(Js.text(vector, "payload"), key));
    }
  }

  @Test
  void refusesTheSpecsInvalidPayloadsTellingAnotherVersionApart() {
    for (JsonValue vector : Js.list(VECTORS, "invalid_decrypt")) {
      String note = Js.text(vector, "note");
      // A payload of another version is told apart, an empty one too: a record of it is of a
      // newer format (SPEC.md §12, "Opening", step 2).
      Class<? extends Exception> expected =
          note.startsWith("unknown encryption version") || Js.text(vector, "payload").isEmpty()
              ? Nip44.UnknownVersionException.class
              : Nip44.DecryptException.class;
      assertThrows(
          expected,
          () -> decrypt(Js.text(vector, "payload"), Js.hex(vector, "conversation_key")),
          note);
    }
    assertThrows(Nip44.UnknownVersionException.class, () -> decrypt("#future", new byte[32]));
  }

  @Test
  void keepsAPlaintextsLeadingByteOrderMarkUtf8IsDecodedStrictly() throws Exception {
    byte[] key = RANDOM.next(32);
    assertEquals("﻿{}", decrypt(Nip44.encrypt("﻿{}", key, RANDOM.next(32)), key));
  }

  @Test
  void opensNoRecordWhosePlaintextStartsWithAByteOrderMarkNotJson() {
    byte[] signer = new byte[32];
    Arrays.fill(signer, (byte) 3);
    byte[] encryptionKey = new byte[32];
    Arrays.fill(encryptionKey, (byte) 4);
    String address = "ab".repeat(32);
    long createdAt = 1_791_378_000L;
    String author = Curve.publicKeyOf(signer);
    NostrEvent sealed =
        Records.seal(
            "t", new JsonNumber(1), signer, encryptionKey, address, createdAt, List.of(), RANDOM);
    Records.Opened<JsonValue> opened =
        Records.open(sealed.toJson(), "t", encryptionKey, address, author, (value, event) -> value);
    assertNotNull(opened);
    assertEquals(new JsonNumber(1), opened.value());
    // The same record, its plaintext after a byte order mark, signed by its author.
    String text = "﻿" + "{\"t\":\"t\",\"v\":1}";
    String plaintext = text + " ".repeat(256 - me.sattle.checkout.internal.Text.utf8Length(text));
    NostrEvent event =
        Events.sign(
            30078,
            createdAt,
            List.of(List.of("d", address)),
            Nip44.encrypt(plaintext, encryptionKey, RANDOM.next(32)),
            signer,
            RANDOM);
    assertNull(
        Records.open(event.toJson(), "t", encryptionKey, address, author, (value, e) -> value));
  }

  @Test
  void drawsAFreshNonceEachTime() {
    byte[] signer = RANDOM.next(32);
    byte[] key = RANDOM.next(32);
    String address = "ab".repeat(32);
    NostrEvent first =
        Records.seal(
            "t", new JsonNumber(1), signer, key, address, 1_791_378_000L, List.of(), RANDOM);
    NostrEvent second =
        Records.seal(
            "t", new JsonNumber(1), signer, key, address, 1_791_378_000L, List.of(), RANDOM);
    assertNotEquals(first.content(), second.content());
    assertNotEquals(first.sig(), second.sig());
  }
}
