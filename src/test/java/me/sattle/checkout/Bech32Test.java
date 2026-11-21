package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import me.sattle.checkout.internal.lightning.Bech32;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;

/**
 * Bech32 is US-ASCII, in one case (BIP-173), and that case is ASCII's (SPEC.md §6, §19): Unicode's
 * mapping reads the Kelvin sign (U+212A) as a k, so that a text that is no invoice would decode as
 * one. As checkout-node's bech32 tests.
 */
class Bech32Test {
  private static final String INVOICE =
      Js.text(Js.get(Js.spec("vectors/app/app.json"), "realInvoice"), "invoice");
  private static final String UPPER = INVOICE.toUpperCase(Locale.ROOT);
  private static final int AT = UPPER.indexOf('K', UPPER.lastIndexOf('1'));

  /** The invoice in upper case, {@code character} in place of its first K after the separator. */
  private static String withAt(String character) {
    return UPPER.substring(0, AT) + character + UPPER.substring(AT + 1);
  }

  @Test
  void readsAnInvoiceInUpperCase() {
    assertNotNull(Bolt11.decode(INVOICE));
    assertEquals(Bolt11.decode(INVOICE), Bolt11.decode(UPPER));
  }

  @Test
  void refusesTheKelvinSignInPlaceOfAK() {
    assertTrue(AT > 0);
    // What Unicode's mapping reads it as: the invoice itself.
    assertEquals(INVOICE, withAt("K").toLowerCase(Locale.ROOT));
    assertNull(Bolt11.decode(withAt("K")));
  }

  @Test
  void refusesAnyCharacterBeyondUsAscii33To126AndMixedCase() {
    for (String character : List.of("é", " ", "İ", "k")) {
      assertNull(Bech32.decode(withAt(character), Bech32.BECH32), character);
    }
  }
}
