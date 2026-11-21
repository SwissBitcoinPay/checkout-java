package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.Test;

/**
 * No serializer writes the key (SPEC.md §27; cross-port item 12): the client and its builder give
 * nothing of the integration key, of a key derived from it, nor of the CoinGecko key, to the
 * serializers a Java application commonly calls on what it logs or returns: Gson (private fields),
 * Jackson (its getters, and its fields when an application makes them visible; Spring Boot's
 * default writes an object without properties as {@code {}}), Java's serialization, and {@code
 * toString}. Every field that leads to a key is transient, and none of them has a getter. Run on
 * the class path too, where Gson and Jackson may read every field of a class.
 */
class SerializersTest {
  private static final String COINGECKO_KEY = "CG-demo-key-of-the-shop";
  private final TestMerchant merchant = new TestMerchant();

  /** The objects that hold the key: the client, and its builder with every option given. */
  private List<Supplier<Object>> holders() {
    return List.of(
        () -> SattlemeClient.builder().key(merchant.key()).coingeckoApiKey(COINGECKO_KEY).build(),
        () ->
            SattlemeClient.builder()
                .key(merchant.key())
                .coingeckoApiKey(COINGECKO_KEY)
                .rateSource(RateSource.custom(currency -> RateQuote.of("69012")))
                .relayTimeoutMs(1000)
                .random(new SecureRandom()),
        () -> {
          SattlemeClient.Builder built =
              SattlemeClient.builder().key(merchant.key()).coingeckoApiKey(COINGECKO_KEY);
          built.build();
          return built;
        });
  }

  /** Whether {@code text} holds a key, in any spelling a serializer writes bytes or a string in. */
  private boolean leaks(String text) {
    byte[] key = merchant.integrationKey;
    StringBuilder numbers = new StringBuilder();
    for (int i = 0; i < key.length; i += 1) numbers.append(i == 0 ? "" : ",").append(key[i]);
    String keyPart = merchant.key().split("\\.")[1];
    return text.contains(keyPart)
        || text.contains(merchant.key())
        || text.contains(Base64.getEncoder().encodeToString(key))
        || text.contains(HexFormat.of().formatHex(key))
        || text.replace(" ", "").contains(numbers)
        || text.contains(COINGECKO_KEY);
  }

  @Test
  void gsonWritesNothingOfTheKey() {
    Gson gson = new Gson();
    org.junit.jupiter.api.Assertions.assertAll(
        holders().stream()
            .map(holder ->
                (org.junit.jupiter.api.function.Executable)
                    () -> {
                      Object object = holder.get();
                      String written = gson.toJson(object);
                      assertFalse(leaks(written), "the key written: " + written);
                      assertEquals("{}", written, object.getClass().getName());
                    }));
  }

  @Test
  void jacksonWritesNothingOfTheKey() throws Exception {
    // Its default: no property, nothing written (an error that holds no key).
    ObjectMapper jackson = new ObjectMapper();
    for (Supplier<Object> holder : holders()) {
      InvalidDefinitionException error =
          assertThrows(
              InvalidDefinitionException.class, () -> jackson.writeValueAsString(holder.get()));
      assertFalse(leaks(String.valueOf(error)), String.valueOf(error));
    }
    // As Spring Boot configures it, and with every field visible: an empty object.
    ObjectMapper lenient = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    ObjectMapper fields =
        new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    // Each holder alone: a holder's leak is told whatever another one does.
    org.junit.jupiter.api.Assertions.assertAll(
        holders().stream()
            .map(holder ->
                (org.junit.jupiter.api.function.Executable)
                    () -> {
                      Object object = holder.get();
                      assertEquals("{}", lenient.writeValueAsString(object));
                      String written = fields.writeValueAsString(object);
                      assertFalse(leaks(written), "the key written: " + written);
                      assertEquals("{}", written);
                    }));
  }

  @Test
  void javaSerializationWritesNothingOfTheKey() throws Exception {
    for (Supplier<Object> holder : holders()) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        assertThrows(NotSerializableException.class, () -> out.writeObject(holder.get()));
      }
      assertFalse(leaks(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)));
    }
  }

  @Test
  void toStringWritesNothingOfTheKey() {
    for (Supplier<Object> holder : holders()) {
      String text = holder.get().toString();
      assertFalse(leaks(text), text);
    }
    assertEquals(
        "SattlemeClient[team=" + merchant.team + ", relays=" + merchant.keyRelays + "]",
        SattlemeClient.builder().key(merchant.key()).build().toString());
  }
}
