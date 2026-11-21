package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.support.Eventually;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.Owner;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checkout links (SPEC.md §30): asked with the order, published on a majority of the merchant's
 * relays before the invoice is declared, read by the merchant's web app with what its URL holds
 * alone. As checkout-node's checkout tests.
 */
class CheckoutTest {
  private static final String ORIGIN = "https://pay.example";

  private TestMerchant merchant;

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(
            100, Map.of("relays", merchant.keyRelays, "checkout", Js.obj("origin", ORIGIN))),
        100);
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  private SattlemeClient client() {
    return merchant
        .clientBuilder()
        .relayTimeoutMs(1000)
        .rateSource(RateSource.custom(currency -> RateQuote.of("99000")))
        .build();
  }

  /**
   * Every event the relays received, in the order the first relay received them, once each relay
   * received as many: a publication ends on a majority, the last relay's copy may be on its way.
   */
  private List<NostrEvent> received() throws InterruptedException {
    merchant.net.settle();
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (merchant.keyRelays.stream()
                .map(url -> merchant.net.get(url).received.size())
                .distinct()
                .count()
            > 1
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    return merchant.net.get(merchant.keyRelays.get(0)).received;
  }

  private static boolean isCheckout(NostrEvent event) {
    return event.tags().stream().anyMatch(tag -> tag.get(0).equals("expiration"));
  }

  /** A link read as the page reads it, with its record from the relays. */
  record Opened(
      Checkout.Link fragment,
      NostrEvent event,
      List<NostrEvent> copies,
      JsonValue value,
      Checkout.CheckoutRecord record) {}

  private Opened openLink(String url) throws InterruptedException {
    assertTrue(url.startsWith(ORIGIN + "/pay#c."), url);
    Checkout.Link fragment =
        assertInstanceOf(
            Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    String address = Derive.checkoutAddress(fragment.linkKey());
    // On a majority when the call returned, on the others once their writes ended.
    merchant.net.settle();
    Eventually.until(() ->
        fragment.relays().stream().allMatch(relay -> linkAt(relay, fragment, address) != null));
    List<NostrEvent> copies = new ArrayList<>();
    for (String relay : fragment.relays()) copies.add(linkAt(relay, fragment, address));
    NostrEvent event = copies.get(0);
    assertNotNull(event, "on the relays");
    JsonValue value =
        CheckoutVectorTest.openValue(
            event, Derive.checkoutEncryptionKey(fragment.linkKey()), "checkout");
    return new Opened(fragment, event, copies, value, Checkout.parseValue(value));
  }

  private NostrEvent linkAt(String relay, Checkout.Link fragment, String address) {
    return merchant.net.get(relay).events.stream()
        .map(Events::checked)
        .filter(event ->
            event != null
                && event.pubkey().equals(fragment.author())
                && address.equals(event.tag("d")))
        .findFirst()
        .orElse(null);
  }

  @Nested
  class CreateInvoiceWithCheckout {
    @Test
    void publishesTheLinkBeforeTheDeclarationAndGivesItsUrl() throws Exception {
      Invoice invoice =
          client()
              .createInvoice(
                  Order.builder("ORD-1042")
                      .fiat("24.50", "CHF")
                      .note("Order 1042")
                      .checkout(true)
                      .redirectUrl("https://shop.example/orders/1042/thanks")
                      .build());
      TestMerchant.MadeInvoice made = merchant.invoices.values().iterator().next();
      assertEquals("https://shop.example/orders/1042/thanks", invoice.redirectUrl());
      // JSON as is, with exactly the fields of SPEC.md "Data model": no secret of the link.
      JsonObject json = (JsonObject) Js.parse(invoice.toJson());
      assertEquals(
          Stream.of(
                  "ref",
                  "bolt11",
                  "paymentHash",
                  "verifyUrl",
                  "onchainAddress",
                  "paymentUri",
                  "sats",
                  "fiat",
                  "rate",
                  "note",
                  "createdAt",
                  "expiresAt",
                  "checkoutUrl",
                  "redirectUrl")
              .sorted()
              .toList(),
          json.names().stream().sorted().toList());

      Opened opened = openLink(invoice.checkoutUrl());
      // On every relay of the merchant, which the URL names.
      assertEquals(merchant.keyRelays, opened.fragment().relays());
      for (NostrEvent copy : opened.copies()) {
        assertNotNull(copy);
        assertEquals(opened.event().id(), copy.id());
      }
      // The link first, then the declaration: a link never names an invoice its URL was not
      // given for.
      assertEquals(
          List.of("link", "declaration"),
          received().stream().map(e -> isCheckout(e) ? "link" : "declaration").toList());
      // Its own author, not the sale's; dropped by the relays a week after the invoice's end.
      assertNotEquals(
          Derive.Sale.of(
                  merchant.integrationKey,
                  new Derive.Registry(merchant.registryKey, merchant.reader),
                  "ORD-1042")
              .author,
          opened.event().pubkey());
      assertEquals(
          List.of(
              List.of("d", Derive.checkoutAddress(opened.fragment().linkKey())),
              List.of("expiration", Long.toString(invoice.expiresAt() + 604_800))),
          opened.event().tags());
      assertEquals(merchant.now, opened.event().createdAt());
      assertEquals(
          Js.obj(
              "version",
              1,
              "businessName",
              "Sunrise Bakery",
              "invoice",
              made.pr(),
              "verifyUrl",
              made.verify(),
              "fiat",
              Js.obj("minor", 2450, "digits", 2, "currency", "CHF"),
              "note",
              "Order 1042",
              "redirectUrl",
              "https://shop.example/orders/1042/thanks"),
          opened.value());
      assertEquals(Long.parseLong(invoice.sats()), opened.record().sats());
      assertEquals("shop.example", opened.record().redirectShown());
    }

    @Test
    void writesAnOrderInSatsWithoutAPriceAndWithoutARedirectUrl() throws Exception {
      Invoice invoice =
          client().createInvoice(Order.builder("A-1").sats(49_000).checkout(true).build());
      assertNull(invoice.redirectUrl());
      JsonValue value = openLink(invoice.checkoutUrl()).value();
      assertEquals(Js.of(null), Js.get(value, "fiat"));
      assertEquals("A-1", Js.text(value, "note"));
      assertEquals(Js.of(null), Js.get(value, "redirectUrl"));
    }

    @Test
    void authenticatesTheLinksPublicationAsItsAuthor() throws Exception {
      for (String relay : merchant.keyRelays) {
        LocalRelay.Behaviour auth = new LocalRelay.Behaviour();
        auth.requireAuth = true;
        merchant.net.get(relay).behaviour = auth;
      }
      Invoice invoice =
          client().createInvoice(Order.builder("A-1").sats(100).checkout(true).build());
      Checkout.Link fragment = openLink(invoice.checkoutUrl()).fragment();
      assertTrue(
          merchant.net.get(merchant.keyRelays.get(0)).authenticated.contains(fragment.author()));
    }

    @Test
    void givesANewLinkWithNewKeysToEachInvoice() throws Exception {
      Invoice first = client().createInvoice(Order.builder("A-1").sats(100).checkout(true).build());
      Invoice second =
          client().createInvoice(Order.builder("A-2").sats(100).checkout(true).build());
      Checkout.Link a = openLink(first.checkoutUrl()).fragment();
      Checkout.Link b = openLink(second.checkoutUrl()).fragment();
      assertNotEquals(a.author(), b.author());
      assertFalse(Arrays.equals(a.linkKey(), b.linkKey()));
    }

    @Test
    void publishesNothingMoreWithoutCheckout() throws Exception {
      Invoice invoice = client().createInvoice(Order.builder("A-1").sats(100).build());
      assertNull(invoice.checkoutUrl());
      assertNull(invoice.redirectUrl());
      assertEquals(0, received().stream().filter(CheckoutTest::isCheckout).count());
    }

    @Test
    void refusesCheckoutWhenTheMerchantsAppGivesNoWebAppBeforeAnyInvoice() throws Exception {
      // Absent, null, a string, an origin that is not https.
      List<Function<Map<String, Object>, Map<String, Object>>> invalid =
          List.of(
              fields -> fields,
              fields -> {
                fields.put("checkout", null);
                return fields;
              },
              fields -> {
                fields.put("checkout", "https://pay.example");
                return fields;
              },
              fields -> {
                fields.put("checkout", Js.obj("origin", "http://pay.example"));
                return fields;
              });
      for (int index = 0; index < invalid.size(); index += 1) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("relays", merchant.keyRelays);
        merchant.publishChannel(
            merchant.channelValue(200 + index, invalid.get(index).apply(fields)), 200 + index);
        String ref = "A-" + index;
        assertThrows(
            CheckoutUnavailableException.class,
            () -> client().createInvoice(Order.builder(ref).sats(100).checkout(true).build()));
        // The channel stays valid: an invoice without checkout is made.
        assertNull(
            client().createInvoice(Order.builder("B-" + index).sats(100).build()).checkoutUrl());
      }
      CheckoutUnavailableException error =
          assertThrows(
              CheckoutUnavailableException.class,
              () -> client().createInvoice(Order.builder("C-1").sats(100).checkout(true).build()));
      assertEquals("checkout_unavailable", error.getCode());
      assertFalse(error.isRetryable());
      // Only the invoices made without checkout.
      assertEquals(invalid.size(), merchant.invoices.size());
    }

    /*
     * checkout-node also refuses `checkout: "yes"`, `checkout: 1` and `redirectUrl: 42`: here an
     * order's checkout is a boolean and its redirect URL a String, so neither can be written.
     */
    static Stream<Arguments> invalidOrders() {
      return Stream.of(
          Arguments.of("redirectUrl", null, "https://shop.example/thanks"),
          Arguments.of("redirectUrl", false, "https://shop.example/thanks"),
          Arguments.of("redirectUrl", true, "http://shop.example/thanks"),
          Arguments.of("redirectUrl", true, "javascript://alert(1)"),
          Arguments.of("redirectUrl", true, "https://user:pass@shop.example/"),
          Arguments.of("redirectUrl", true, ""),
          Arguments.of("redirectUrl", true, "https://shop.example/\u0000"));
    }

    @ParameterizedTest(name = "refuses an invalid {0} before any request: checkout {1}, {2}")
    @MethodSource("invalidOrders")
    void refusesAnInvalidFieldBeforeAnyRequest(String param, Boolean checkout, String redirectUrl)
        throws Exception {
      InvalidOrderException error =
          assertThrows(
              InvalidOrderException.class,
              () -> {
                Order.Builder order = Order.builder("A-1").sats(100).redirectUrl(redirectUrl);
                if (checkout != null) order.checkout(checkout);
                client().createInvoice(order.build());
              });
      assertEquals(param, error.getParam());
      assertEquals(List.of(), merchant.http.calls);
    }

    @Test
    void takesANullRedirectUrlAsNone() throws Exception {
      Invoice invoice =
          client()
              .createInvoice(
                  Order.builder("A-1").sats(100).checkout(true).redirectUrl(null).build());
      assertNull(invoice.redirectUrl());
    }

    @Test
    void declaresNothingWhenFewerThanAMajorityTakeTheLink() throws Exception {
      LocalRelay.Behaviour refuseLinks = new LocalRelay.Behaviour();
      refuseLinks.refuseEvent = event -> isCheckout(event) ? "blocked: no" : null;
      merchant.net.get("wss://relay-a.test").behaviour = refuseLinks;
      merchant.net.get("wss://relay-b.test").behaviour = refuseLinks;
      assertThrows(
          NetworkException.class,
          () -> client().createInvoice(Order.builder("A-1").sats(100).checkout(true).build()));
      assertEquals(0, received().stream().filter(e -> !isCheckout(e)).count());
      // The order gets a new invoice at once: nothing was declared.
      merchant.net.get("wss://relay-a.test").behaviour = new LocalRelay.Behaviour();
      merchant.net.get("wss://relay-b.test").behaviour = new LocalRelay.Behaviour();
      assertEquals(
          "A-1",
          client().createInvoice(Order.builder("A-1").sats(100).checkout(true).build()).ref());
      assertEquals(2, merchant.invoices.size());
    }

    @Test
    void givesNoUrlWhenTheDeclarationFailsAfterTheLink() throws Exception {
      LocalRelay.Behaviour refuseDeclarations = new LocalRelay.Behaviour();
      refuseDeclarations.refuseEvent = event -> isCheckout(event) ? null : "blocked: no";
      merchant.net.get("wss://relay-a.test").behaviour = refuseDeclarations;
      merchant.net.get("wss://relay-b.test").behaviour = refuseDeclarations;
      assertThrows(
          NetworkException.class,
          () -> client().createInvoice(Order.builder("A-1").sats(100).checkout(true).build()));
    }

    @Test
    void givesANewLinkToThePendingInvoiceOfAnOrderWithThisCallsRedirectUrl() throws Exception {
      Invoice first =
          client()
              .createInvoice(
                  Order.builder("ORD-7")
                      .fiat("0.02", "CHF")
                      .note("Order 7")
                      .checkout(true)
                      .redirectUrl("https://shop.example/7")
                      .build());
      InvoicePendingException pending =
          assertThrows(
              InvoicePendingException.class,
              () ->
                  client()
                      .createInvoice(
                          Order.builder("ORD-7")
                              .fiat("0.02", "CHF")
                              .checkout(true)
                              .redirectUrl("myshop://orders/7")
                              .build()));
      assertEquals(first.bolt11(), pending.getInvoice().bolt11());
      assertNotNull(pending.getCheckoutUrl());
      assertNotEquals(first.checkoutUrl(), pending.getCheckoutUrl());
      Opened opened = openLink(pending.getCheckoutUrl());
      assertEquals(
          Js.obj(
              "version",
              1,
              "businessName",
              "Sunrise Bakery",
              "invoice",
              first.bolt11(),
              "verifyUrl",
              first.verifyUrl(),
              "fiat",
              Js.obj("minor", 2, "digits", 2, "currency", "CHF"),
              "note",
              "Order 7",
              "redirectUrl",
              "myshop://orders/7"),
          opened.value());
      assertEquals("myshop://", opened.record().redirectShown());
      // Without checkout, no link.
      InvoicePendingException plain =
          assertThrows(
              InvoicePendingException.class,
              () -> client().createInvoice(Order.builder("ORD-7").fiat("0.02", "CHF").build()));
      assertEquals("invoice_pending", plain.getCode());
      assertNull(plain.getCheckoutUrl());
    }

    @Test
    void keepsTheDeclarationAsItWasNoRedirectUrlInTheRegistry() throws Exception {
      client()
          .createInvoice(
              Order.builder("A-1")
                  .sats(100)
                  .checkout(true)
                  .redirectUrl("https://shop.example/thanks")
                  .build());
      NostrEvent declaration =
          received().stream().filter(e -> !isCheckout(e)).findFirst().orElseThrow();
      assertEquals(
          List.of("invoice", "sale", "taker", "verifyUrl", "version"),
          Owner.openAsOwner(declaration, merchant.registryKey, merchant.readerSecret)
              .names()
              .stream()
              .sorted()
              .toList());
    }
  }

  @Nested
  class ALinksParts {
    @Test
    void readsALinksFragmentBackAndOnlyTheFragmentsOfCheckoutLinks() throws Exception {
      byte[] secret = new byte[32];
      Arrays.fill(secret, (byte) 7);
      String author = Curve.publicKeyOf(secret);
      byte[] linkKey = new byte[32];
      Arrays.fill(linkKey, (byte) 8);
      String url =
          Checkout.checkoutUrl(
              ORIGIN, author, linkKey, List.of("wss://relay.example", "wss://nos.lol"));
      Checkout.Link link =
          assertInstanceOf(
              Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
      assertEquals(author, link.author());
      assertArrayEquals(linkKey, link.linkKey());
      assertEquals(List.of("wss://relay.example", "wss://nos.lol"), link.relays());
      assertEquals(new Checkout.Newer(), Checkout.parseFragment("#d.a.b.c"));
      assertEquals(new Checkout.Newer(), Checkout.parseFragment("c2.a.b.c"));
      assertEquals(new Checkout.Invalid(), Checkout.parseFragment("c.a.b"));
      // A payment link of the point of sale: its author first, 43 characters.
      assertEquals(
          new Checkout.Other(), Checkout.parseFragment("A".repeat(43) + "." + "B".repeat(43)));
      assertEquals(Hex.encode(linkKey), Hex.encode(link.linkKey()));
    }

    @Test
    void showsTheHostOfAnHttpsUrlAndTheSchemeOfAnAppsLink() throws Exception {
      assertEquals("shop.example:8443", Checkout.redirectShown("https://shop.example:8443/a?b#c"));
      assertEquals("myshop://", Checkout.redirectShown("myshop://orders/42"));
      assertNull(Checkout.redirectShown("https://shop.example\\@evil.example"));
    }
  }
}
