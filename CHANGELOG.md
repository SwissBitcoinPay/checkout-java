# Changelog

All notable changes to `me.sattle:checkout`. Versions follow
[Semantic Versioning](https://semver.org/); tags are `vX.Y.Z`.

Released versions are signed and published by CI, once a maintainer approves the release
(`.github/workflows/release.yml`): to the GitHub Release of their tag and to the Maven repository
`https://maven.sattle.me`.

## 1.0.1 - 2026-11-21

The first published version: the code of 1.0.0, unchanged. 1.0.0 was tagged, but its GitHub
Release was made without its files, and a release cannot change once published: 1.0.0 is not
published, neither on its GitHub Release nor at `https://maven.sattle.me`.

## 1.0.0 - 2026-11-21

First version, stable. Follows version 1.0.0 of the specification
([sattleme/checkout-spec](https://github.com/sattleme/checkout-spec)) and passes its
conformance vectors. Java 17.0.11, 21.0.3, 22.0.1 or later.

- **The client**, `SattlemeClient`: reads the integration key and the merchant (`merchant()`),
  makes the invoice of an order and declares it in the merchant's registry (`createInvoice()`),
  checks its payment (`getStatus()`, `waitForPayment()`), finds the invoices of an order again
  (`findInvoices()`). Made for a server that lives for one call: nothing is kept between calls.
  Every method blocks its thread and has an asynchronous twin (`…Async`, a `CompletableFuture`); an
  interrupted call, or a cancelled future, stops its requests and fails with Java's cancellation.
- **One order, one invoice at a time**, checked against the registry (`InvoicePendingException`,
  `OrderPaidException`): the payment of an invoice that may still be paid is checked first, even
  when it is the only one, so that an invoice already paid is never handed out again; of several,
  the one a majority of the relays holds is handed out.
- **Orders in sats or in a currency** (the ISO 4217 currencies), converted exactly and rounded up
  to the sat, at the price of a list of rate sources asked in turn: CoinGecko, mempool.space,
  Coinbase, Kraken, OpenNode, or your own. `Currencies.currencyDigits(code)`, the specification's
  `currencyDigits`, gives the decimals of each currency of the table (empty for any other text),
  with no client.
- **Lightning payments**: an invoice from the merchant's Lightning address (LUD-16, LUD-21 verify
  URL).
- **On-chain payments**: from 1 000 sats, a taproot deposit address of the merchant's wallet leased
  for the order, with a single `bitcoin:` QR code (`paymentUri`). A lease is published
  unconfirmed, read again on every relay of the merchant, then marked confirmed: only a confirmed
  lease gives the order its address, and no order leases an address another order holds or a
  witness names. Payment checked on one explorer (paid at 3 confirmations on what it shows), with
  fee bumps (RBF) and the `unconfirmed` state; `waitForPayment` watches the address on the
  explorer's WebSocket.
- **The merchant's Bitcoin node**: the explorer is mempool.space, or the merchant's own node
  serving mempool.space's API (an Umbrel, a server of its own) when the integration's channel
  names one (`bitcoinNode`): its URL by a rule of its own (`https`, or `http` to a host of a local
  network, an IPv4 address allowed), its fallback when it does not answer (`"mempool"`:
  mempool.space, the node asked after it for 60 s once it failed in a call; `"none"`: never
  mempool.space, no address leased and `NetworkException`, never `expired`, until it answers),
  its WebSocket while `waitForPayment` waits (`ws://` or `wss://`), and, with `payers`, the node
  the pages of checkout links read too (in the link's record). Nothing to configure.
- **Witnesses** of the transactions each check sees, which name the invoice they are of: a
  transaction a witness of another invoice names counts for nothing.
- **The rest of an on-chain underpayment**, payable in Lightning (and on-chain from 1 000 sats),
  shown on the order's checkout links; an order paid both ways is `paid` with `method: "mixed"`.
- **The merchant's tolerance of on-chain underpayments** (0 to 10 %), read in the integration's
  channel and kept in the lease of each order's address.
- **The merchant's acceptance** of an order underpaid on-chain at what it received, made by the
  merchant in its app: read by every check on every relay of the merchant, and counted only when a
  majority of them hold it; the order is then paid with `received` below its amount.
- **Checkout links**: `Order.builder(ref)…checkout(true).redirectUrl(url)` gives a `checkoutUrl`,
  a page of the merchant's web app where the customer pays, then is sent to `redirectUrl`.
- **Stored invoices**: `Invoice.toJson()` is the JSON of the specification, which every
  implementation reads; `Invoice.fromJson` and `InvoiceToCheck.fromJson` read it back, from this
  package or another.
- **Outbound requests**: https URLs only (a lowercase host name, no IP address, no host under
  `localhost`), but for the merchant's Bitcoin node (its own rule, above), no redirect followed, at most 1 MiB read of an answer's body and 384 KiB of its
  head (Java's own bound: a Java without one is refused), and
  `User-Agent: sattleme-Checkout/1`. The relays' WebSocket handshake follows no redirect, carries
  the same User-Agent and offers no compression; a read keeps only what opens with its keys, once
  per event and without what a relay adds to it, so a relay cannot make the server's memory grow.
- **Typed errors**: `SattlemeException` and one subclass per code, with a stable `getCode()` and
  whether they are retryable.
