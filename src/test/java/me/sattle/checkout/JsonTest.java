package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The package's own JSON reader and writer (SPEC.md §2): strict RFC 8259 with the semantics of
 * JavaScript's {@code JSON.parse} (the last of members of one name counts, a number is a double
 * whatever its spelling, a lone surrogate escape kept), nested 64 deep at most, and a writer that
 * writes what {@code JSON.stringify} writes.
 */
class JsonTest {
  private static JsonValue parse(String text) {
    return Json.parseOrNull(text);
  }

  private static String nested(int depth, char open, char close, String inside) {
    return String.valueOf(open).repeat(depth) + inside + String.valueOf(close).repeat(depth);
  }

  private static String nestedObjects(int depth) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < depth; i += 1) text.append("{\"a\":");
    text.append("1");
    text.append("}".repeat(depth));
    return text.toString();
  }

  @Test
  void readsATextNested64DeepAndNotOne65Deep() {
    // Arrays and objects counted together (SPEC.md §2), whatever the reader.
    assertNotNull(parse(nested(64, '[', ']', "1")));
    assertNull(parse(nested(65, '[', ']', "1")));
    assertNotNull(parse(nestedObjects(64)));
    assertNull(parse(nestedObjects(65)));
    StringBuilder mixed = new StringBuilder();
    for (int i = 0; i < 32; i += 1) mixed.append("[{\"a\":");
    String closing = "}]".repeat(32);
    assertNotNull(parse(mixed + "1" + closing));
    assertNull(parse(mixed + "[1]" + closing));
    // Brackets in a string are no nesting.
    assertNotNull(parse("[\"" + "[".repeat(1000) + "\"]"));
    assertNotNull(parse("{\"" + "{[".repeat(500) + "\":\"" + "]}".repeat(500) + "\"}"));
    // An escaped quote does not end the string.
    assertNotNull(parse("[\"\\\"" + "[".repeat(100) + "\"]"));
  }

  @Test
  void neverOverflowsTheStackOnADeepText() {
    // A relay or an HTTP body may send anything: a million brackets are no JSON, never an error.
    assertNull(parse("[".repeat(1_000_000)));
    assertNull(parse("[".repeat(1_000_000) + "]".repeat(1_000_000)));
    assertNull(parse("{\"a\":".repeat(200_000) + "1" + "}".repeat(200_000)));
    assertNull(Json.parseBody(("[".repeat(500_000)).getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void readsAnIntegerOfAnyLengthAsADouble() {
    String digits = "1" + "0".repeat(4999);
    assertEquals(new JsonNumber(Double.POSITIVE_INFINITY), parse(digits));
    assertEquals(new JsonNumber(Double.NEGATIVE_INFINITY), parse("-" + digits));
    assertEquals(new JsonNumber(Double.POSITIVE_INFINITY), parse("1e400"));
    assertEquals(new JsonNumber(Double.NEGATIVE_INFINITY), parse("-1e400"));
    assertEquals(new JsonNumber(0), parse("1e-400"));
    // 2^53 + 1 is read as JavaScript reads it: the nearest double.
    assertEquals(new JsonNumber(9007199254740992.0), parse("9007199254740993"));
    // 300 digits: a double that JavaScript reads too, exactly rounded.
    String long300 = "123456789".repeat(33) + "123";
    assertEquals(new JsonNumber(Double.parseDouble(long300)), parse(long300));
    assertEquals(new JsonNumber(5e2), parse("500.0"));
    assertEquals(new JsonNumber(5e2), parse("5E+2"));
  }

  @Test
  void readsAMemberNameHoldingU0000AsAnOrdinaryName() {
    JsonObject object = (JsonObject) parse("{\"\\u0000\":1,\"a\\u0000b\":2}");
    assertNotNull(object);
    assertEquals(new JsonNumber(1), object.get("\u0000"));
    assertEquals(new JsonNumber(2), object.get("a\u0000b"));
    assertEquals(Js.of("\u0000"), parse("\"\\u0000\""));
  }

  @ParameterizedTest(name = "refuses {0}")
  @ValueSource(
      strings = {
        "01",
        "-01",
        "00",
        "[01]",
        "{\"a\":01}",
        "1.",
        ".1",
        "+1",
        "1e",
        "0x10",
        "[1,]",
        "{\"a\":1,}",
        "[,1]",
        "\u00a01",
        "1\u00a0",
        "[\u00a01]",
        "\ufeff1",
        "[1\ufeff]",
        "{\"a\"\u00a0:1}",
        "\u000b1",
        "\u000c1",
        "\u20281",
        "NaN",
        "Infinity",
        "-Infinity",
        "'a'",
        "{a:1}",
        "[1 2]",
        "\"a",
        "\"\\x41\"",
        "\"\\u12\"",
        "\"\u0001\"",
        "",
        " ",
        "[1]x",
        "tru",
        "nul"
      })
  void refusesWhatIsNotJson(String text) {
    assertNull(parse(text), text);
  }

  @Test
  void takesTheFourWhitespacesOfJsonAndTheLastOfMembersOfOneName() {
    assertEquals(Js.arr(1, 2), parse(" \t\n\r[ 1 ,\t2 ]\r\n"));
    assertEquals(Js.obj("a", 2), parse("{\"a\":1,\"a\":2}"));
    // A lone surrogate escape stays one, as in JavaScript (a check of a text finds it later).
    assertEquals(new JsonString("\ud800"), parse("\"\\ud800\""));
    assertEquals(new JsonString("😀"), parse("\"\\ud83d\\ude00\""));
  }

  @Test
  void writesWhatJsonStringifyWrites() {
    // -0 as JSON.stringify writes it: 0.
    assertEquals(
        "{\"a\":[1,2.5,0,1e+21,1.5e-7,null,true,\"\\u0000\\n\\\"\\\\\"]}",
        Json.write(
            Js.obj("a", List.of(1, 2.5, -0.0, 1e21, 1.5e-7, Js.of(null), true, "\u0000\n\"\\"))));
    assertEquals("\"\\ud800\"", Json.write(new JsonString("\ud800")));
    assertEquals("\"😀\"", Json.write(new JsonString("😀")));
    assertEquals("\"\u2028\"", Json.write(new JsonString("\u2028")));
    assertEquals("[]", Json.write(new JsonArray()));
    assertEquals("123456789012345680000", Json.write(new JsonNumber(123456789012345678901.0)));
    assertTrue(Json.write(new JsonNumber(0.1 + 0.2)).equals("0.30000000000000004"));
  }

  /** Milliseconds {@code read} takes, its second run (the first warms the JIT up). */
  private static long timed(Runnable read) {
    read.run();
    long started = System.nanoTime();
    read.run();
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  @Test
  void readsWhatARelayOrAServerSendsInTimeLinearInItsSize() {
    // SPEC.md §13: reading a message or an answer costs time linear in its size. A reader that
    // indexes a string from its start at each escape is quadratic: 9 s and 130 s for these (the
    // Ruby port's); a linear one takes milliseconds.
    String zeros = "\"" + "\\u0000".repeat((1 << 20) / 6) + "\"";
    String mixed = "\"" + "\\u0000\u00e9".repeat((1 << 20) / 8) + "\"";
    for (String text : List.of(zeros, mixed)) {
      byte[] body = text.getBytes(StandardCharsets.UTF_8);
      assertTrue(
          body.length >= (1 << 20) - 8 && body.length <= (1 << 20) + 2, body.length + " bytes");
      long ms = timed(() -> assertNotNull(Json.parseBody(body)));
      assertTrue(ms < 2000, ms + " ms for an answer of 1 MiB");
      // A relay's event of such a content: read, and its id checked (its serialization escapes
      // each U+0000 again).
      String event =
          Js.write(
              Js.obj(
                  "id", "00".repeat(32),
                  "pubkey", "11".repeat(32),
                  "created_at", 1_791_378_000L,
                  "kind", 30078,
                  "tags", List.of(),
                  "content", Js.text(Js.obj("c", Json.parseBody(body)), "c"),
                  "sig", "22".repeat(64)));
      String message = "[\"EVENT\",\"ab\"," + event + "]";
      long eventMs =
          timed(() ->
              assertNull(
                  me.sattle.checkout.internal.nostr.Events.withValidId(
                      Json.array(Json.parseOrNull(message)).get(2))));
      assertTrue(eventMs < 2000, eventMs + " ms for a relay's message of 1 MiB");
    }
  }

  @Test
  void ignoresOneByteOrderMarkOfAnHttpBodyAndKeepsItElsewhere() {
    byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    byte[] body = new byte[bom.length + 2];
    System.arraycopy(bom, 0, body, 0, 3);
    body[3] = '[';
    body[4] = ']';
    assertEquals(new JsonArray(), Json.parseBody(body));
    // Elsewhere (a relay's message, a decrypted record): U+FEFF is no whitespace (SPEC.md §6).
    assertNull(parse("\ufeff[]"));
    assertNull(Json.strictUtf8(new byte[] {(byte) 0xc0, (byte) 0x80}));
    assertEquals("\ufeff", Json.strictUtf8(bom));
  }
}
