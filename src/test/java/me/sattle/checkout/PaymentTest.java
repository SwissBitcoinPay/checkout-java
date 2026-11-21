package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Checking a Lightning payment (SPEC.md §22, §23), as checkout-node's payment tests. */
class PaymentTest {
  private TestMerchant merchant;
  private SattlemeClient sattleme;
  private Invoice invoice;

  @BeforeEach
  void start() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    sattleme = merchant.client();
    invoice = sattleme.createInvoice(Order.builder("P-1").sats(2100).durationSeconds(600).build());
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  /** breez.tips's verify answer for the invoice, replaced. */
  private void verifyAnswers(JsonValue json, int status) {
    merchant.override =
        uri -> uri.getPath().contains("/verify/") ? FakeHttp.Answer.json(status, json) : null;
  }

  private void verifyAnswers(JsonValue json) {
    verifyAnswers(json, 200);
  }

  private TestMerchant.MadeInvoice made() {
    return merchant.invoices.get(invoice.paymentHash());
  }

  private static WaitOptions every250ms() {
    return WaitOptions.builder().intervalMs(250).build();
  }

  @Nested
  class GetStatus {
    @Test
    void isOpenThenPaidWithThePreimageTheProofOfPayment() throws Exception {
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      merchant.payInvoice(invoice.paymentHash());
      assertEquals(new PaymentStatus.PaidLightning(made().preimage()), sattleme.getStatus(invoice));
      // A look, without state: from the stored invoice, as JSON.
      assertEquals("paid", sattleme.getStatus(InvoiceToCheck.fromJson(invoice.toJson())).state());
    }

    @Test
    void readsTheInvoiceWhateverItsCaseAndAnUpperCasePreimage() throws Exception {
      TestMerchant.MadeInvoice made = made();
      verifyAnswers(
          Js.obj(
              "status",
              "OK",
              "settled",
              true,
              "preimage",
              made.preimage().toUpperCase(java.util.Locale.ROOT),
              "pr",
              made.pr().toUpperCase(java.util.Locale.ROOT)));
      assertEquals(new PaymentStatus.PaidLightning(made.preimage()), sattleme.getStatus(invoice));
    }

    @Test
    void refusesAnAnswerAboutAnotherInvoiceAndAWrongPreimage() throws Exception {
      TestMerchant.MadeInvoice made = made();
      Invoice other =
          sattleme.createInvoice(Order.builder("P-2").sats(2100).durationSeconds(600).build());
      verifyAnswers(
          Js.obj(
              "status", "OK", "settled", true, "preimage", made.preimage(), "pr", other.bolt11()));
      assertThrows(InvalidInvoiceException.class, () -> sattleme.getStatus(invoice));
      verifyAnswers(
          Js.obj("status", "OK", "settled", true, "preimage", "11".repeat(32), "pr", made.pr()));
      assertThrows(InvalidInvoiceException.class, () -> sattleme.getStatus(invoice));
      verifyAnswers(Js.obj("status", "OK", "settled", true, "preimage", null, "pr", made.pr()));
      assertThrows(InvalidInvoiceException.class, () -> sattleme.getStatus(invoice));
      verifyAnswers(Js.obj("status", "OK", "settled", true, "preimage", made.preimage()));
      assertThrows(InvalidInvoiceException.class, () -> sattleme.getStatus(invoice));
    }

    @Test
    void isExpired120SecondsAfterTheInvoicesEndNotBefore() throws Exception {
      merchant.now = invoice.expiresAt() + 119;
      assertEquals("open", sattleme.getStatus(invoice).state());
      merchant.now = invoice.expiresAt() + 120;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
    }

    @Test
    void isExpiredOnNotFoundAfterTheGraceOnly() throws Exception {
      verifyAnswers(Js.obj("status", "ERROR", "reason", "Not found"));
      merchant.now = invoice.expiresAt() + 119;
      assertEquals("open", sattleme.getStatus(invoice).state());
      merchant.now = invoice.expiresAt() + 120;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
      verifyAnswers(Js.obj("status", "ERROR", "reason", "Not found"), 404);
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
    }

    @Test
    void keepsTheInvoiceOpenOnAnyOtherErrorBeforeTheGraceRetriesAfter() throws Exception {
      verifyAnswers(Js.obj("status", "ERROR", "reason", "Internal server error"));
      assertEquals("open", sattleme.getStatus(invoice).state());
      merchant.now = invoice.expiresAt() + 600;
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
    }

    @Test
    void throwsNetworkRetryableWhenBreezTipsDoesNotAnswer() throws Exception {
      for (int status : new int[] {500, 502, 429}) {
        verifyAnswers(Js.obj("status", "ERROR", "reason", "Not found"), status);
        NetworkException error =
            assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
        assertTrue(error.isRetryable());
        assertEquals("network", error.getCode());
      }
      merchant.override =
          uri -> {
            if (uri.getPath().contains("/verify/")) {
              throw new UncheckedIOException(new IOException("fetch failed"));
            }
            return null;
          };
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      merchant.override =
          uri -> uri.getPath().contains("/verify/") ? FakeHttp.Answer.text(200, "<html>") : null;
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
    }

    @Test
    void goesOnOnceTheIntegrationIsRemovedReadingNoRelay() throws Exception {
      // SPEC.md §14: the check of an invoice without an address reads no relay (its verify URL is
      // the Lightning address's, and needs nothing of the key): a removal does not stop it.
      merchant.publishChannel(Js.obj("version", 1, "status", "removed", "updatedAt", 200), 200);
      assertThrows(RevokedException.class, () -> sattleme.merchant());
      merchant.net.settle();
      List<Integer> queries = new java.util.ArrayList<>();
      for (String url : merchant.keyRelays) queries.add(merchant.net.get(url).queries.size());
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      merchant.payInvoice(invoice.paymentHash());
      assertEquals(new PaymentStatus.PaidLightning(made().preimage()), sattleme.getStatus(invoice));
      assertEquals(
          new PaymentStatus.PaidLightning(made().preimage()),
          sattleme.waitForPayment(invoice, every250ms()));
      for (int i = 0; i < merchant.keyRelays.size(); i += 1) {
        assertEquals(
            queries.get(i).intValue(), merchant.net.get(merchant.keyRelays.get(i)).queries.size());
      }
    }

    @Test
    void refusesAStoredInvoiceThatIsNotWhatCreateInvoiceGave() {
      List<InvoiceToCheck> changed =
          List.of(
              InvoiceToCheck.of(
                  invoice.ref(),
                  invoice.bolt11(),
                  "00".repeat(32),
                  invoice.verifyUrl(),
                  invoice.onchainAddress(),
                  invoice.sats(),
                  invoice.expiresAt()),
              InvoiceToCheck.of(
                  invoice.ref(),
                  invoice.bolt11(),
                  invoice.paymentHash(),
                  invoice.verifyUrl(),
                  invoice.onchainAddress(),
                  invoice.sats(),
                  invoice.expiresAt() + 3600),
              InvoiceToCheck.of(
                  invoice.ref(),
                  invoice.bolt11(),
                  invoice.paymentHash(),
                  "http://breez.tips/x",
                  invoice.onchainAddress(),
                  invoice.sats(),
                  invoice.expiresAt()),
              InvoiceToCheck.of(
                  invoice.ref(),
                  "lnbc1",
                  invoice.paymentHash(),
                  invoice.verifyUrl(),
                  invoice.onchainAddress(),
                  invoice.sats(),
                  invoice.expiresAt()));
      for (InvoiceToCheck stored : changed) {
        InvalidOrderException error =
            assertThrows(InvalidOrderException.class, () -> sattleme.getStatus(stored));
        assertEquals("invoice", error.getParam());
      }
    }
  }

  @Nested
  class WaitForPayment {
    @Test
    void checksUntilTheInvoiceIsPaidNetworkErrorsRetried() throws Exception {
      List<String> seen = new CopyOnWriteArrayList<>();
      AtomicInteger calls = new AtomicInteger();
      merchant.override =
          uri -> {
            if (!uri.getPath().contains("/verify/")) return null;
            int call = calls.incrementAndGet();
            if (call == 2) throw new UncheckedIOException(new IOException("fetch failed"));
            if (call == 3) merchant.payInvoice(invoice.paymentHash());
            return null;
          };
      PaymentStatus result =
          sattleme.waitForPayment(
              invoice,
              WaitOptions.builder()
                  .intervalMs(250)
                  .onStatus(status -> seen.add(status.state()))
                  .build());
      assertEquals("paid", result.state());
      assertEquals(List.of("open", "paid"), seen);
    }

    @Test
    void logsAFailureEachCheckMeetsAgainOnceAndAgainOnlyAfterItStopped() throws Exception {
      List<String> warnings = new CopyOnWriteArrayList<>();
      SattlemeClient logged =
          merchant.clientBuilder().logger(new TestMerchant.ListLogger(warnings)).build();
      // Down for 3 checks, up for 1, down for 2 again, then paid.
      boolean[] down = {true, true, true, false, true, true};
      AtomicInteger calls = new AtomicInteger();
      merchant.override =
          uri -> {
            if (!uri.getPath().contains("/verify/")) return null;
            int call = calls.incrementAndGet();
            if (call > down.length) merchant.payInvoice(invoice.paymentHash());
            return call <= down.length && down[call - 1] ? FakeHttp.Answer.text(503, "down") : null;
          };
      assertEquals("paid", logged.waitForPayment(invoice, every250ms()).state());
      assertEquals(down.length + 1, calls.get());
      String warning =
          "me.sattle:checkout: the payment check failed (breez.tips answered HTTP 503 without an"
              + " LNURL answer); checked again";
      assertEquals(List.of(warning, warning), warnings);
    }

    @Test
    void stopsAtExpired() throws Exception {
      merchant.now = invoice.expiresAt() + 120;
      assertEquals(new PaymentStatus.Expired("0"), sattleme.waitForPayment(invoice, every250ms()));
    }

    @Test
    void stopsWhenItsThreadIsInterrupted() throws Exception {
      // The signal of checkout-node: here an interrupt, InterruptedException, never a §5 error.
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      Thread waiting =
          new Thread(() -> {
            try {
              sattleme.waitForPayment(invoice, every250ms());
            } catch (Throwable e) {
              thrown.set(e);
            }
          });
      waiting.start();
      Thread.sleep(300);
      waiting.interrupt();
      waiting.join(5000);
      assertInstanceOf(InterruptedException.class, thrown.get());
    }

    @Test
    void stopsWhenItsFutureIsCancelled() throws Exception {
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(invoice, every250ms());
      Thread.sleep(300);
      assertTrue(waiting.cancel(true));
      assertThrows(CancellationException.class, () -> waiting.get(5, TimeUnit.SECONDS));
      assertTrue(waiting.isCancelled());
    }

    @Test
    void stopsOnAnAnswerThatIsNotAboutThisInvoice() {
      verifyAnswers(Js.obj("status", "OK", "settled", false, "pr", "lnbc1other"));
      assertThrows(
          InvalidInvoiceException.class, () -> sattleme.waitForPayment(invoice, every250ms()));
    }
  }
}
