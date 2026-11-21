package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Test;

/**
 * The pinned copy of the specification (spec/, PORTING.md of checkout-spec): every file of
 * spec/PIN.json is there with its SHA-256, and nothing else. It changes only by taking a newer
 * version of checkout-spec ({@code scripts/spec import <clone>}), never by hand.
 */
class SpecPinTest {
  @Test
  void isTheCopyPinJsonPins() throws Exception {
    assertEquals(List.of(), SpecCopy.check(Js.SPEC));
  }
}
