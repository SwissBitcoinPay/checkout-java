package me.sattle.checkout;

import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * The currencies an order may be priced in, with their decimals (SPEC.md §25): ISO 4217 List One
 * (SIX Group), published 2026-09-17, without funds codes, precious metals, SDR, test and "no
 * currency" codes. The decimals are those the merchant's app uses (CLDR, through ECMAScript {@code
 * Intl}), because the app compares a sale's declared decimals with its own: for 13 currencies they
 * are not ISO 4217's (AFN, ALL, IQD, IRR, KPW, LAK, LBP, MGA, MMK, RSD, SOS, SYP, YER have 0). The
 * same table as the specification's {@code currencies.json} (a test checks it): never the
 * platform's locale data ({@link java.util.Currency}), which differs between versions.
 */
public final class Currencies {
  private Currencies() {}

  /** Each currency's code followed by its decimals. */
  private static final String[] TABLE = {
    "AED2", "AFN0", "ALL0", "AMD2", "AOA2", "ARS2", "AUD2", "AWG2", "AZN2", "BAM2",
    "BBD2", "BDT2", "BHD3", "BIF0", "BMD2", "BND2", "BOB2", "BRL2", "BSD2", "BTN2",
    "BWP2", "BYN2", "BZD2", "CAD2", "CDF2", "CHF2", "CLP0", "CNY2", "COP2", "CRC2",
    "CUP2", "CVE2", "CZK2", "DJF0", "DKK2", "DOP2", "DZD2", "EGP2", "ERN2", "ETB2",
    "EUR2", "FJD2", "FKP2", "GBP2", "GEL2", "GHS2", "GIP2", "GMD2", "GNF0", "GTQ2",
    "GYD2", "HKD2", "HNL2", "HTG2", "HUF2", "IDR2", "ILS2", "INR2", "IQD0", "IRR0",
    "ISK0", "JMD2", "JOD3", "JPY0", "KES2", "KGS2", "KHR2", "KMF0", "KPW0", "KRW0",
    "KWD3", "KYD2", "KZT2", "LAK0", "LBP0", "LKR2", "LRD2", "LSL2", "LYD3", "MAD2",
    "MDL2", "MGA0", "MKD2", "MMK0", "MNT2", "MOP2", "MRU2", "MUR2", "MVR2", "MWK2",
    "MXN2", "MYR2", "MZN2", "NAD2", "NGN2", "NIO2", "NOK2", "NPR2", "NZD2", "OMR3",
    "PAB2", "PEN2", "PGK2", "PHP2", "PKR2", "PLN2", "PYG0", "QAR2", "RON2", "RSD0",
    "RUB2", "RWF0", "SAR2", "SBD2", "SCR2", "SDG2", "SEK2", "SGD2", "SHP2", "SLE2",
    "SOS0", "SRD2", "SSP2", "STN2", "SVC2", "SYP0", "SZL2", "THB2", "TJS2", "TMT2",
    "TND3", "TOP2", "TRY2", "TTD2", "TWD2", "TZS2", "UAH2", "UGX0", "USD2", "UYU2",
    "UZS2", "VED2", "VES2", "VND0", "VUV0", "WST2", "XAF0", "XCD2", "XCG2", "XOF0",
    "XPF0", "YER0", "ZAR2", "ZMW2", "ZWG2"
  };

  private static final Map<String, Integer> DIGITS = digits();

  private static Map<String, Integer> digits() {
    Map<String, Integer> digits = new TreeMap<>();
    for (String entry : TABLE) {
      digits.put(entry.substring(0, 3), Integer.parseInt(entry.substring(3)));
    }
    return Map.copyOf(digits);
  }

  /**
   * The decimals of the currency whose code is exactly {@code code} in the table (SPEC.md §4,
   * {@code currencyDigits}): 2 for {@code "CHF"}, 0 for {@code "JPY"}, 3 for {@code "KWD"}. Empty
   * for any other text: a code in lower case or with a space around it, a fund ({@code CHE}), a
   * metal ({@code XAU}), a test code ({@code XTS}, {@code XXX}), {@code BTC}, the empty text, null.
   * Needs no client and asks nothing: whether a shop may offer its customers to pay in its
   * currency, and in how many decimals its prices go to {@link Order.Builder#fiat(String, String)}.
   *
   * @param code an ISO 4217 code in upper case
   * @return its decimals, or empty when an order cannot be priced in it
   */
  public static OptionalInt currencyDigits(String code) {
    Integer digits = code == null ? null : DIGITS.get(code);
    return digits == null ? OptionalInt.empty() : OptionalInt.of(digits);
  }

  /**
   * Every currency an order may be priced in, with its decimals.
   *
   * @return the table, by ISO 4217 code (unmodifiable)
   */
  public static Map<String, Integer> all() {
    return DIGITS;
  }
}
