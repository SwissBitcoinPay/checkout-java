/**
 * The sattle.me checkout SDK: a shop's server takes Lightning and on-chain payments for a sattle.me
 * merchant. Its API is the package {@code me.sattle.checkout}; every other package is internal and
 * may change in any version.
 *
 * <p>secp256k1-kmp (ACINQ's bindings to libsecp256k1) splits one package across two jars, which the
 * module path refuses: its jars stay on the class path, which this module reads at run time.
 */
module me.sattle.checkout {
  requires transitive java.net.http;

  exports me.sattle.checkout;
}
