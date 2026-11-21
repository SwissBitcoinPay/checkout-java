/**
 * Take Lightning and on-chain payments for a sattle.me merchant from an online shop's server.
 *
 * <p>{@link me.sattle.checkout.SattlemeClient} makes the invoice of each order (a Lightning
 * invoice, a deposit address of the merchant's wallet, or both), declares it in the merchant's
 * registry and checks its payment. Everything it gives and takes is JSON as the specification
 * writes it (<a href="https://github.com/sattleme/checkout-spec">sattleme/checkout-spec</a>,
 * SPEC.md): every amount a string of digits, every date an integer of Unix seconds.
 */
package me.sattle.checkout;
