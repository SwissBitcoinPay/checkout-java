package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.function.Supplier;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;

/**
 * The checks of URLs on long texts: Java's matcher recurses once per repetition of a group, so a
 * text a relay, a Lightning address or a shop gives could overflow the stack of the thread that
 * checks it (an Error, no answer of SPEC.md §5). Each check runs here on a thread of 256 KiB of
 * stack, where a pattern that repeats a group over 2048 characters overflows.
 */
class UrlsTest {
  private static final long STACK = 256 * 1024;

  /** What {@code check} gives on a thread of a small stack; its Error, rethrown. */
  private static Object onASmallStack(Supplier<Object> check) throws InterruptedException {
    Object[] result = new Object[1];
    Throwable[] failure = new Throwable[1];
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                result[0] = check.get();
              } catch (Throwable e) {
                failure[0] = e;
              }
            },
            "small stack",
            STACK);
    thread.start();
    thread.join(30_000);
    assertFalse(thread.isAlive());
    if (failure[0] != null) throw new AssertionError("the check failed", failure[0]);
    return result[0];
  }

  @Test
  void checksAUrlOf2048CharactersWhateverTheStack() throws Exception {
    String path =
        "/" + "a".repeat(Constants.MAX_HTTPS_URL_LENGTH - "https://example.com/".length());
    String url = "https://example.com" + path;
    assertEquals(Constants.MAX_HTTPS_URL_LENGTH, url.length());
    assertNotNull(onASmallStack(() -> Urls.parseHttpsUrl(url)));
    assertEquals("example.com", onASmallStack(() -> Checkout.redirectShown(url)));
    // Escapes too.
    String escaped = "https://example.com/" + "%41".repeat(600);
    assertNotNull(onASmallStack(() -> Urls.parseHttpsUrl(escaped)));
    assertEquals("example.com", onASmallStack(() -> Checkout.redirectShown(escaped)));
    // One character more: refused, as a text of 1 MiB.
    assertNull(onASmallStack(() -> Urls.parseHttpsUrl(url + "a")));
    assertNull(
        onASmallStack(() -> Urls.parseHttpsUrl("https://example.com/" + "a".repeat(1 << 20))));
    // What URL characters are: RFC 3986's, and % with two hex digits.
    assertTrue(Urls.isUrlText("https://a.b/~x?y=1&z=%7e#[]@!$'()*+,;="));
    for (String text :
        new String[] {"", "%", "%4", "%4g", "a%", "a b", "é", "\"", "<", "\\", "^"}) {
      assertFalse(Urls.isUrlText(text), text);
    }
  }

  @Test
  void asksAnHttpsUrlAsItIsItsFragmentDroppedWhatUriRefusesPercentEncoded() {
    // java.net.URI refuses a [ or ] in a path and a second # (SPEC.md §15 takes them): such a
    // verify URL or callback is asked, as fetch asks it.
    assertEquals(
        "https://breez.tips/lnurlp/verify/%5B9c41%5D",
        Urls.requestUri("https://breez.tips/lnurlp/verify/[9c41]").toString());
    assertEquals(
        "https://breez.tips/lnurlp/verify/9c41",
        Urls.requestUri("https://breez.tips/lnurlp/verify/9c41#a#b").toString());
    assertEquals(
        "https://breez.tips/lnurlp/x%5D/invoice?amount=1000&k=[2]/?@",
        Urls.requestUri("https://breez.tips/lnurlp/x]/invoice?amount=1000&k=[2]/?@#[f]")
            .toString());
    // Everything else as it is, escapes included.
    String plain = "https://breez.tips:8443/a-b_c.d~e!f*g'h(i)j,k;l:m$n&o+p=q@r/%7E%c3%a9?x=%41";
    assertEquals(plain, Urls.requestUri(plain).toString());
    assertEquals("https://breez.tips?k=1", Urls.requestUri("https://breez.tips?k=1#x").toString());
    // Every https URL of the vectors: a URI of its host and port.
    for (JsonValue item : Js.list(Js.spec("vectors/https-urls.json"), "urls")) {
      String url = Js.text(item, "url");
      Urls.HttpsUrl parsed = Urls.parseHttpsUrl(url);
      if (parsed == null) continue;
      URI uri = Urls.requestUri(url);
      assertEquals("https", uri.getScheme(), url);
      assertEquals(parsed.host(), uri.getHost(), url);
      assertEquals(parsed.port(), uri.getPort() < 0 ? 443 : uri.getPort(), url);
      assertNull(uri.getRawFragment(), url);
    }
  }

  @Test
  void refusesARelayAddressOfAHundredThousandLabelsWhateverTheStack() throws Exception {
    String many = "wss://" + "a.".repeat(100_000) + "com";
    assertEquals(false, onASmallStack(() -> Urls.isRelayUrl(many)));
    assertEquals(true, onASmallStack(() -> Urls.isRelayUrl("wss://relay.example.com/path")));
  }

  @Test
  void refusesALightningAddressOfAHundredThousandLabelsWhateverTheStack() throws Exception {
    String many = "shop@" + "a.".repeat(100_000) + "com";
    assertNull(onASmallStack(() -> Urls.lightningAddressUrl(many)));
    assertEquals(
        "https://breez.tips/.well-known/lnurlp/shop",
        onASmallStack(() -> Urls.lightningAddressUrl(" Shop@Breez.Tips ")));
    // Its domain: two labels at least, none empty.
    for (String address :
        new String[] {"shop@tips", "shop@.tips", "shop@a..tips", "shop@a.tips."}) {
      assertNull(Urls.lightningAddressUrl(address), address);
    }
  }
}
