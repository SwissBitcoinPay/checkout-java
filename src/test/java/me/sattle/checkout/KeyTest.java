package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The integration's secret key (SPEC.md §8), as checkout-node's key tests. */
class KeyTest {
  private static final JsonValue APP = Js.spec("vectors/app/app.json");
  private static final JsonValue PARSED = Js.get(APP, "parsed");
  private static final String KEY = Js.text(APP, "secretKey");
  private static final String PREFIX = "sattleme_ck_";
  private static final String[] PARTS = KEY.substring(PREFIX.length()).split("\\.");
  private static final String TEAM = PARTS[0];
  private static final String SECRET = PARTS[1];
  private static final String RELAYS = PARTS[2];

  @Test
  void readsTheKeyTheAppMade() {
    IntegrationKey parsed = IntegrationKey.parse(KEY);
    assertEquals(Js.text(PARSED, "team"), parsed.teamPublicKey());
    assertEquals(Js.text(PARSED, "integrationKey"), Hex.encode(parsed.key()));
    assertEquals(Js.strings(PARSED, "relays"), parsed.relays());
  }

  @Test
  void keepsTheKeyOutOfEveryTextAndOfSerialization() {
    String keyHex = Js.text(PARSED, "integrationKey");
    IntegrationKey parsed = IntegrationKey.parse(KEY);
    List<String> texts = new ArrayList<>();
    texts.add(parsed.toString());
    SattlemeClient.Builder builder =
        SattlemeClient.builder().key(KEY).logger(System.getLogger("me.sattle.checkout.test"));
    texts.add(builder.toString());
    SattlemeClient client = builder.build();
    texts.add(client.toString());
    texts.add(String.valueOf(client));
    for (String text : texts) {
      assertFalse(text.contains(SECRET), text);
      assertFalse(text.contains(keyHex), text);
      assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(keyHex), text);
    }
    // Neither the client nor what holds the key can be serialized.
    assertFalse(Serializable.class.isAssignableFrom(SattlemeClient.class));
    assertFalse(Serializable.class.isAssignableFrom(SattlemeClient.Builder.class));
    assertFalse(Serializable.class.isAssignableFrom(IntegrationKey.class));
  }

  /** {@code part} (base64url of UTF-8) with a byte order mark before its text. */
  private static String withBom(String part) {
    byte[] text = Base64.getUrlDecoder().decode(part);
    byte[] bytes = new byte[text.length + 3];
    bytes[0] = (byte) 0xef;
    bytes[1] = (byte) 0xbb;
    bytes[2] = (byte) 0xbf;
    System.arraycopy(text, 0, bytes, 3, text.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static Stream<Arguments> damaged() {
    return Stream.of(
        Arguments.of("empty", ""),
        Arguments.of("another prefix", KEY.replace(PREFIX, "sattleme_other_")),
        Arguments.of("a later format's prefix", KEY.replace(PREFIX, "sattleme_ck2_")),
        Arguments.of("the prefix sattleme_ without ck_", KEY.replace(PREFIX, "sattleme_")),
        Arguments.of("no prefix", KEY.substring(PREFIX.length())),
        Arguments.of(
            "a link holding its parts",
            "https://pay.example/pay#" + TEAM + "." + SECRET + "." + RELAYS),
        Arguments.of("a part missing", PREFIX + TEAM + "." + SECRET),
        Arguments.of("a part too many", KEY + "." + RELAYS),
        Arguments.of("a key cut short", KEY.substring(0, KEY.length() - 3)),
        Arguments.of("a team cut short", PREFIX + TEAM.substring(1) + "." + SECRET + "." + RELAYS),
        Arguments.of("a key too long", PREFIX + TEAM + "." + SECRET + "AA." + RELAYS),
        Arguments.of("padding", PREFIX + TEAM + "=." + SECRET + "." + RELAYS),
        Arguments.of(
            "standard base64", PREFIX + "+" + TEAM.substring(1) + "." + SECRET + "." + RELAYS),
        Arguments.of(
            "unused bits set",
            PREFIX + TEAM.substring(0, TEAM.length() - 1) + "b." + SECRET + "." + RELAYS),
        Arguments.of("no relay", PREFIX + TEAM + "." + SECRET + "."),
        Arguments.of("a relay that is not one", PREFIX + TEAM + "." + SECRET + ".bG9jYWxob3N0"),
        Arguments.of("spaces", " " + KEY),
        // Strict UTF-8 keeps it: "﻿relay…" is no relay's host (SPEC.md §8, step 5).
        Arguments.of(
            "a relays part starting with a byte order mark",
            PREFIX + TEAM + "." + SECRET + "." + withBom(RELAYS)));
  }

  @ParameterizedTest(name = "refuses {0}, without the key in the message")
  @MethodSource("damaged")
  void refusesADamagedKeyWithoutTheKeyInTheMessage(String name, String text) {
    InvalidKeyException error =
        assertThrows(InvalidKeyException.class, () -> IntegrationKey.parse(text));
    assertEquals("invalid_key", error.getCode());
    assertFalse(error.isRetryable());
    String message = String.valueOf(error.getMessage());
    assertFalse(message.contains(SECRET), message);
    assertFalse(message.contains(Js.text(PARSED, "integrationKey")), message);
    assertFalse(error.toString().contains(SECRET));
    // And so does the client, at construction.
    InvalidKeyException built =
        assertThrows(InvalidKeyException.class, () -> SattlemeClient.builder().key(text).build());
    assertFalse(String.valueOf(built.getMessage()).contains(SECRET));
  }

  @Test
  void refusesAKeyAtConstruction() {
    assertThrows(InvalidKeyException.class, () -> SattlemeClient.builder().key("nope").build());
    assertThrows(InvalidKeyException.class, () -> SattlemeClient.builder().build());
  }
}
