# me.sattle:checkout

Take Lightning and on-chain payments for a sattle.me merchant from an online shop's
**server**, in Java: the invoice of each order (a Lightning invoice, a deposit address of the
merchant's wallet, or both), its declaration in the merchant's registry, and the check of its
payment.

- Everything runs on your server, which holds the integration's secret key. The customer's
  browser only ever gets the invoice and its address: on your page, or on the merchant's web app
  through a [checkout link](#checkout-links), if you ask for one.
- Payments go straight to the merchant's wallet. sattle.me runs no server and no API for
  integrations: this package talks to the merchant's Lightning address (breez.tips), to the
  merchant's Nostr relays and, for [on-chain payments](#on-chain-payments), to one block explorer
  (mempool.space, or [the merchant's own Bitcoin node](#the-merchants-bitcoin-node) when its app
  names one) and BlockCypher; nothing else (and to the rate source you choose, for prices in a
  currency).
- Amounts are exact: strings of digits (sats, a currency's minor units), never floating-point.

The protocol is specified, for implementations in other languages, in
[sattleme/checkout-spec](https://github.com/sattleme/checkout-spec) (`SPEC.md`),
with conformance vectors every implementation passes. This version implements its version 1.0.0
(`spec/PIN.json`).

## Requirements

- Java 17.0.11, 21.0.3, 22.0.1 or later (tested on 17, 21 and 25), on a **server**: Linux
  (x86-64, arm64), macOS (Intel, Apple silicon) or Windows (x86-64), the platforms libsecp256k1
  ships for in its jars ([Dependencies](#dependencies)). Java's own HTTP client bounds the head of
  an answer (its status line and headers) from these updates on, at `jdk.http.maxHeaderSize` bytes
  (384 KiB unless set): earlier ones (Java 18 to 20 included) read a head of any size into memory,
  and `build()` refuses them (`InvalidOptionException`, `httpClient`), as it refuses that setting
  at 0 or less (no bound) or above 10 MiB.
- A clock synchronized by NTP: invoices more than 5 minutes off the server's clock are refused.
- An integration's secret key: the merchant creates the integration in the sattle.me app
  ("Integrations") and gives you its key, `sattleme_ck_…`.

The package is published to its own Maven repository, `https://maven.sattle.me`, not to Maven
Central (its dependencies are on Maven Central). Maven:

```xml
<repositories>
  <repository>
    <id>sattleme</id>
    <url>https://maven.sattle.me</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>me.sattle</groupId>
  <artifactId>checkout</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle (Kotlin DSL): the repository for `me.sattle` alone, and `me.sattle` from it alone:

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.sattle.me") }
        filter { includeGroup("me.sattle") }
    }
}

dependencies {
    implementation("me.sattle:checkout:1.0.0")
}
```

With Maven, its resolver's remote repository filtering does the same (a `groupId-sattleme.txt`
naming `me.sattle`); without it, Maven may ask `maven.sattle.me` for other artifacts too, which it
does not hold (it holds `me.sattle:checkout` only). Each file is signed (`.asc`) by the key
`7E58 A413 3B5D EA68 CFDE  97B6 E57C D18C EB43 024D` ([signing-key.asc](signing-key.asc), also at
`https://maven.sattle.me/signing-key.asc`), and each release's files are attached to its GitHub
Release, which cannot change once published.

On the class path there is nothing more to do. As a module (`requires me.sattle.checkout;`), keep
secp256k1-kmp's jars on the class path: they split one package across two jars, which the module
path refuses; the module reads them from there.

On Java 24 and later, give the class path native access: secp256k1-kmp loads libsecp256k1 with
`System.load` from there. Start Java with `--enable-native-access=ALL-UNNAMED` (or put
`Enable-Native-Access: ALL-UNNAMED` in the manifest of your executable jar). Without it, Java prints
a warning when the package first loads the library, and says that a later release will refuse to
load it (JEP 472).

## The key is a secret

Keep the integration's key on the server only: in an environment variable or a secrets vault,
never in a web page, an app, a code repository, `application.properties` or a log. With it, anyone
can make invoices that pay the merchant and mark sales as the integration's. It can never spend the
merchant's funds nor read their wallet.

If it leaks, the merchant removes the integration in the app ("Remove the integration") and
creates another one: the old key then makes no invoice (`createInvoice`), finds none
(`findInvoices`) and reads no merchant (`merchant()`): `RevokedException`. A check of an invoice it
made (`getStatus`, `waitForPayment`) still finds its Lightning payment: an invoice without an
address is checked as before, and one with an address is `RevokedException` unless its Lightning
invoice was paid (SPEC.md §14). The merchant settles by hand the orders still being paid on-chain.

**One key, one shop.** The merchant creates one integration for each shop (each system with its
own orders' references), and two shops never share a key: the sale of an order is derived from the
key and the order's reference, so two shops with one key would share their references, and an
order of one would be found paid, or pending, with an invoice of the other. The servers of one
shop share its key.

The package never writes the key, nor any key derived from it, nor the CoinGecko key, in an
exception, a log or a `toString()`: `SattlemeClient` prints the team's public key and the relays
only, and its builder nothing. Nor do the serializers an application calls on what it logs or
returns: every field of the client and of its builder that leads to a key is `transient` and has
no getter, so Gson and Jackson (its fields made visible too) write them as `{}` (Jackson's default
refuses an object without properties), and neither is `Serializable`.

## Quick start

```java
import me.sattle.checkout.*;

// Nothing to connect nor to close: each call reads the merchant, opens one connection per relay
// and closes it when it is done. One client for the whole application: it is safe to share.
SattlemeClient sattleme =
    SattlemeClient.builder().key(System.getenv("SATTLEME_INTEGRATION_KEY")).build();

// When the customer checks out, or when the payment page asks again:
String invoiceOf(ShopOrder order) throws InterruptedException {
  if (order.invoiceJson() != null) return order.invoiceJson(); // one order, one invoice at a time
  String json;
  try {
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder(order.ref()) // "ORD-1042": the order's reference, its sale in the app
                .sats(49_000) // or: .fiat("24.50", "CHF")
                .durationSeconds(900) // 60 to 3600
                .note("Order " + order.number()) // shown to the merchant, 100 characters at most
                .build());
    json = invoice.toJson();
  } catch (InvoicePendingException e) {
    // An invoice of this order may still be paid (say, after a crash): keep that one. It is the
    // registry's copy: the same fields, without `rate`.
    json = e.getInvoice().toJson();
  }
  db.saveInvoice(order, json);
  return json;
}

// The payment page gets the invoice alone: its paymentUri ("bitcoin:bc1p…?amount=…&lightning=lnbc…")
// and expiresAt. When it asks for the order's status (every 1 to 2 seconds):
String statusOf(ShopOrder order) throws InterruptedException {
  PaymentStatus status = sattleme.getStatus(InvoiceToCheck.fromJson(order.invoiceJson()));
  switch (status) {
    // In Lightning: keep the preimage, the proof of payment. On-chain: the transactions. Mixed
    // (on-chain, its rest in Lightning): both.
    case PaymentStatus.Paid paid -> db.markPaid(order, paid.toJson());
    // Never paid: the order may get a new invoice. `received` above "0": an on-chain
    // underpayment the merchant received, to settle with the customer.
    case PaymentStatus.Expired expired -> db.saveInvoice(order, null);
    default -> {}
  }
  return status.state(); // "open" | "unconfirmed" | "paid" | "expired"
}
```

(`switch` on a sealed interface takes Java 21; on Java 17, test `status instanceof
PaymentStatus.Paid paid`.)

`createInvoice` gives an `Invoice`, whose `toJson()` is the JSON to store with the order:

```json
{
  "ref": "ORD-1042",
  "bolt11": "lnbc490u1p…",
  "paymentHash": "9c41…",
  "verifyUrl": "https://breez.tips/lnurlp/verify/9c41…",
  "onchainAddress": "bc1p…",
  "paymentUri": "bitcoin:bc1p…?amount=0.00049&lightning=lnbc490u1p…",
  "sats": "49000",
  "fiat": null,
  "rate": null,
  "note": "Order 1042",
  "createdAt": 1791378000,
  "expiresAt": 1791378900,
  "checkoutUrl": null,
  "redirectUrl": null
}
```

`bolt11` (with `paymentHash` and `verifyUrl`) is null for an order paid on-chain only,
`onchainAddress` for one paid in Lightning only; `fiat` is `{"amount": "24.50", "currency": "CHF",
"minor": "2450", "digits": 2}` for a price in a currency, `rate` (`{"source": "coingecko",
"currency": "CHF", "price": "69012", "fetchedAt": …, "time": …}`) the rate it was converted at.
Every amount is a string of digits, every date an integer of Unix seconds, an absent value `null`,
never left out: the JSON of the specification, which every implementation reads, in any language.
`Invoice.fromJson(json)` reads it back whole; `InvoiceToCheck.fromJson(json)` reads what a payment
check needs (of an `Invoice` or a `DeclaredInvoice`, made by this package or another
implementation).

**Write them with `toJson()`, never with another serializer.** `toJson()` writes the JSON of the
specification (SPEC.md §3), whatever the type you hold it as: `Invoice`, `DeclaredInvoice`, each
`PaymentStatus` (`Open`, with its `Rest`, `Unconfirmed`, `PaidLightning`, `PaidOnchain`,
`PaidMixed`, `Expired`), `Merchant`, `Fiat` and `RateInfo`. They are records, which Jackson, Gson
or JSON-B write as they see them, not as §3 does: Jackson writes a status without its `state` and
`method`, and with a `final` of its own. Store, log and send that text: in a web framework, as the
body of the answer (in Spring, a method that `produces = "application/json"` and returns
`status.toJson()`).

**Stored invoices are your shop's.** Check only the invoices your shop stored itself with their
orders, where only it writes (its database), never one that comes from the customer (a form field,
a URL, a cookie): `getStatus` checks that a stored invoice is well formed (SPEC.md §22), never that
this integration made it. An invoice someone else wrote, with a Lightning invoice of their own that
they paid, reads as `paid` (SPEC.md §27).

Instead of polling from your page's requests, a background job may wait for the payment:

```java
PaymentStatus last =
    sattleme.waitForPayment(
        InvoiceToCheck.fromJson(order.invoiceJson()),
        WaitOptions.builder()
            .intervalMs(2000) // 250 to 60 000
            .onStatus(status -> log.info(status.state()))
            .build());
// last: PaidLightning(preimage) | PaidOnchain(received, txids) |
//       PaidMixed(received, txids, preimages) | Expired(received)
```

A network failure only delays the next check. One that each check meets again (an explorer or the
Lightning address that does not answer…) is reported to your logger once, and again only after it
stopped.

### Threads and cancellation

Every method blocks the calling thread until it is done (a virtual thread's too), and has an
asynchronous twin that runs it on a thread of its own: `createInvoiceAsync`, `getStatusAsync`,
`waitForPaymentAsync`, `findInvoicesAsync`, `merchantAsync`, each a `CompletableFuture`.

A cancelled call stops its requests, closes its connections and fails with Java's cancellation,
never with a `SattlemeException`: interrupt the thread of a blocking call and it throws
`InterruptedException`; cancel the future of an asynchronous one (`future.cancel(true)`) and it
ends with a `CancellationException`, the call's thread interrupted. To bound a call, wait for its
future with a timeout (`future.get(30, TimeUnit.SECONDS)`), then cancel it.

A call runs its parts at the same time, on threads of the package (daemon threads, never the
calling one): what you inject (the `HttpClient`, the `WebSocketFactory`, the logger, the clock and
the `SecureRandom`) is called from several of them within one call, and from several calls at once
when your server makes them so. Each must be safe to call so; the JDK's `HttpClient`,
`SecureRandom`, loggers and clocks are.

Once a call returned, or failed, nothing it started runs on, with one exception (SPEC.md §13): the
relays of a publication past its majority may finish their own request, NIP-42's one
authentication and its one write again included, each within `relayTimeoutMs`. They still use the
`WebSocketFactory`'s connections, the clock and the `SecureRandom` after the call returned. What
Java cannot stop ends by itself: the resolution of a host's name under way (nothing interrupts it;
the request it then starts is aborted at once), and a WebSocket's opening handshake under way
(Java's WebSocket cannot abort one: it ends within `relayTimeoutMs`, and its connection is then
dropped at once, nothing sent over it). A custom rate source runs on a thread of the call, which
is interrupted once the call no longer needs its price: the call returns only once it returned.

### One order, one invoice at a time

- An order keeps its invoice until it has **expired**: `getStatus` says `expired` 120 seconds
  after the invoice's end (a payment of its last seconds may be reported that late). Only then may
  the order get a new invoice. `createInvoice` checks it in the merchant's registry and throws
  `InvoicePendingException` (with that invoice) while an earlier invoice of the same `ref` may
  still be paid (or its on-chain payment is being confirmed), or `OrderPaidException` (with its
  `getStatus()`, and the `getPreimage()` of a Lightning payment) when one was paid.
- Only the order that holds an invoice is marked paid with it, in the database transaction that
  marks it paid. Keep the `preimage` (the proof of a Lightning payment) or the `txids` (of an
  on-chain one) and the `rate` the amount was converted at, to answer a customer or an audit.
- Invoices of the same `ref` are one sale in the merchant's app. One order, one `ref`: never
  reused for another order by anything that holds the key (a test copy of the shop, a restored
  database included), and never taken from what the customer sends (make it on your server).
- After a failure, `findInvoices(ref)` lists the invoices declared for an order, from the
  merchant's registry.
- "One at a time" holds within one `SattlemeClient` and against the registry: when several
  processes may make the invoice of the same order at once, lock the order (in your database)
  around `createInvoice`. In a store whose reads may lag behind its writes (an eventually
  consistent one, as some hosted platforms give), read the lock and what it protects with reads
  that see the latest writes (consistent reads).

### Showing the invoice

This package is for servers only. The shop's page shows the invoice with its own code:

- a QR code of `paymentUri`, with any QR library: `bitcoin:<address>?amount=…&lightning=<bolt11>`
  with an address (BIP-21, the "unified QR" wallets read: they pay in Lightning or on-chain),
  `lightning:<bolt11>` without one;
- an "Open in wallet" link to the same URI;
- the time left until `expiresAt`, then the status your server gives (`open`, `unconfirmed`,
  `paid`, `expired`). With an on-chain underpayment, `open` carries what was `received` and its
  `rest`: show its `paymentUri` as the QR code (the rest in Lightning, and on-chain to the same
  address from 1 000 sats), until `expiresAt`.

The page talks to your server only: never give it the key, nor ask it to reach breez.tips or the
relays. Or send the customer to a checkout link instead (below).

## Checkout links

Instead of showing the invoice on your page, you may send the customer to a page of the merchant's
sattle.me web app made for this invoice: its QR code, a button that opens the customer's
wallet, the time left, then "Paid" or "Expired". Once the invoice is paid, the page sends the
customer back to your `redirectUrl`, 7 seconds later or at once with a tap.

```java
Invoice invoice =
    sattleme.createInvoice(
        Order.builder("ORD-1042")
            .fiat("24.50", "CHF")
            .note("Order 1042") // shown to the customer too
            .checkout(true)
            .redirectUrl("https://shop.example/orders/1042/thanks") // or "myshop://orders/1042"
            .build());
// Store it with the order, then redirect the customer's browser to invoice.checkoutUrl():
// "https://…/pay#c.…" (nothing after "#" is ever sent to the web app's host).
```

- **Optional, per order.** Without `checkout(true)`, nothing changes. `redirectUrl` alone is
  refused (`InvalidOrderException`, `getParam()` `"redirectUrl"`); without one, the page shows the
  receipt and sends the customer nowhere.
- **Not a proof of payment.** Anyone can open your redirect URL. When the customer arrives there,
  check the order with `getStatus` (or `waitForPayment`) on your server, and mark it paid only
  then, as without checkout.
- **What the page gets**: a record the package publishes on a majority of the merchant's relays,
  before the invoice is declared: the invoice, its verify URL, its deposit address (with its amount
  and dates), the business's name, the price, the note and your redirect URL. Never the key, the
  merchant's Lightning address nor its registry. It is signed by a key of that link alone,
  forgotten once it is published: nobody, you included, can change a link afterwards. Relays that
  honor NIP-40 drop it a week after the invoice's end.
- **The rest of an underpayment**: with an address, the record also carries the key and the public
  key with which the page reads the invoices of the rest your server asks for (On-chain payments,
  below): the page shows the rest's invoice as soon as a check of your server published it, so
  keep checking the payment while the customer pays (`waitForPayment`, or `getStatus` from your
  page's polling). Only the integration key signs them.
- **Who sees the customer**: the customer's browser loads the web app, reads the record from the
  merchant's relays and checks the payment with the merchant's Lightning address (breez.tips):
  they see its IP address, which they never do when your page shows the invoice.
- **Redirect URLs**: `https://` with a lowercase host name (no IP address, no `localhost` nor a
  name under it, no `user:password@`), or an app's link (`myshop://…`), 2048 characters at most,
  every character of RFC 3986 (anything else percent-encoded, host names in punycode). Refused:
  `http:`, browser and file schemes, other browsers' schemes (`x-safari-https:`, `googlechrome:`,
  `firefox:`…, which would open a web page whose host the page cannot show: a web page is an
  `https` URL), `intent:`, payment schemes (`lightning:`, `bitcoin:`…), calls and messages. The
  page shows "Redirecting to shop.example" (or "myshop://") on its button. Prefer universal links
  (iOS) and App Links (Android), which are `https` URLs: browsers do not open an app on their own
  after a delay (Chrome on Android refuses it), so for an app's link the customer taps the button.
- **One order, one invoice at a time** holds with checkout links too. When an invoice of the
  order may still be paid, `InvoicePendingException` carries, with `checkout(true)`, a new link to
  it: `e.getCheckoutUrl()`. Send the customer there.
- **Availability**: the merchant's app says in the integration's channel where its web app is.
  An app without a web app gives none: `createInvoice` with `checkout(true)` throws
  `CheckoutUnavailableException`; show the invoice on your page instead.

## On-chain payments

From 1 000 sats, an order may also be paid on-chain, to a deposit address of the merchant's
wallet. Nothing to ask for: `createInvoice` leases an address when the amount allows it, and the
Invoice's `paymentUri` shows both methods in one QR code.

| Amount                                          | Methods                                                          |
| ----------------------------------------------- | ---------------------------------------------------------------- |
| below 1 000 sats                                | Lightning (outside its bounds: `InvalidOrderException`)          |
| 1 000 sats or more, within the Lightning bounds | Lightning and on-chain (Lightning only without an address)       |
| 1 000 sats or more, outside them                | on-chain only (`OnchainUnavailableException` without an address) |

- **The lot**: your server has no wallet and makes no address. The merchant's app gives the
  integration a lot of up to 100 taproot addresses (`bc1p…`) of its wallet, and fills it again
  whenever the merchant opens the app. `merchant().onchainAddresses()` says how many it holds (0:
  no on-chain payment yet).
- **One address, one order**: an order leases an address in the merchant's registry until its
  invoice's end plus 120 seconds; two servers never keep the same one. The lease is published, read
  again on every relay of the merchant (it is confirmed when no other order holds the address),
  then marked confirmed: only then is the address the order's. An address the explorer shows paid
  already, or that a witness (below) names, is skipped.
- **Unconfirmed, then paid**: `getStatus` says `unconfirmed` once enough was sent in time, then
  `paid` (`PaidOnchain`) at 3 confirmations on the explorer (mempool.space, or the merchant's
  Bitcoin node): about 30 minutes. Ship the order only once it is `paid`. `waitForPayment` watches
  the address on the explorer's WebSocket meanwhile; a server that cannot wait that long checks
  again later with `getStatus` (`createInvoice` for the same `ref` throws
  `InvoicePendingException` while it is being confirmed).
- **In time**: a transaction counts for the order when it came while the lease ran. The package
  records each transaction it sees in the merchant's registry (a witness), so that the merchant's
  app tells the same order paid: check the payment while the customer pays (your page's polling,
  or `waitForPayment`), until the invoice's end and its 120 seconds. A transaction no check saw by
  then is told by BlockCypher's time; one received after the end is late: it reaches the merchant,
  but pays no order. A replacement (RBF, a fee bump) of a payment in time counts for it. A
  transaction counts for one invoice at most: one that a witness of another invoice names counts
  for nothing here.
- **Underpaid**: `received` is what came in time. Below the amount (and unless the merchant's
  tolerance or acceptance, below, accepted it), the invoice stays `open` until its end, then
  `expired` with `received` above "0": the merchant received it, settle it with the customer. An
  overpayment is `paid`.
- **The merchant's tolerance**: the merchant may accept, in its app, an on-chain payment a little
  short of the amount (up to 10 %) as paying the order in full. An order keeps the tolerance of its
  creation, whatever the merchant sets later. A payment short by no more than it is
  `unconfirmed`, then `paid`, with `received` below the amount: ship the order as for any `paid`
  status. One short by more is underpaid: its whole rest is asked. The tolerance is the
  merchant's: the package never gives it to your server, nor to your customer; the page of a
  checkout link learns only that this payment counts (an acceptance record), and sends the
  customer back to your shop.
- **The merchant's acceptance**: the merchant may also accept, by hand in its app, what an order
  underpaid on-chain received as paying it in full: one short by more than its tolerance, which
  the merchant would rather settle at what came. Nothing to configure, nothing to call: every
  check (`getStatus`, `waitForPayment`, and `createInvoice`'s check of an order's earlier
  invoices) reads it on every relay of the merchant with the order's witnesses, and counts it only
  when a majority of those relays hold it. The order is then `unconfirmed`, then `paid`
  (`PaidOnchain`, or `PaidMixed` with invoices of the rest), with `received` below the amount, as
  with the tolerance: ship it as for any `paid` status. `waitForPayment` sees it at its next check
  of the on-chain part (within 60 s), the `rest()` still given until then. Only the merchant's
  team can make one: neither your server's key nor a customer can. The page of a checkout link
  learns of it as of the tolerance, through an acceptance record. The one exception to a final
  `expired`: when a relay that holds the acceptance fails at a check after the lease's end, that
  check may give `expired`, and a later one, which the relay answers, `unconfirmed`, then `paid`;
  if you cancelled the order meanwhile, settle it by hand.
- **The rest, in Lightning**: the check that sees an underpayment (`getStatus`, `waitForPayment`)
  asks the merchant's Lightning address for an invoice of the rest, until the invoice's end,
  declares it in the merchant's registry and publishes it for the order's checkout links, whose
  page shows it at once. `open` carries it in `rest()`:

  ```json
  {
    "state": "open",
    "expiresAt": 1791378900,
    "received": "20000",
    "rest": {
      "sats": "29000",
      "bolt11": "lnbc290u1p…",
      "paymentUri": "bitcoin:bc1p…?amount=0.00029&lightning=lnbc290u1p…"
    }
  }
  ```

  `received` is what came in time, `rest.sats` what is left, `rest.bolt11` its invoice (null while
  there is none: asked at the next check), `rest.paymentUri` one QR code. From 1 000 sats the rest
  is payable on-chain to the same address too; below, in Lightning only (`paymentUri` is
  `lightning:…`, or null while its invoice is asked). No invoice of the rest is asked with less
  than a minute left, nor outside the Lightning address's bounds; a failure is logged as a
  warning, never thrown: the next check asks again. Once the rest is paid, the order is
  `unconfirmed` until its on-chain part is 3 confirmations deep, then `paid` with
  `PaidMixed`: keep its `txids` and the `preimages` of the invoices of the rest.

- **Who sees what**: the explorer (mempool.space, or the merchant's Bitcoin node, and
  mempool.space too when the node fails and its fallback allows it) sees your server's IP address
  and the addresses and transactions it asks about or watches; BlockCypher, a transaction no check
  saw in time. Never a key. A payment counts as made on what that one explorer shows.

### The merchant's Bitcoin node

The merchant may have the blockchain read from a node of its own that serves mempool.space's API
(an Umbrel, a server of its own) rather than from mempool.space. It sets it in its app, which
writes it in the integration's channel: nothing to configure on your server. Every call reads it
with the channel; every read of an address and of the chain's height (when an address is leased,
at each check), and `waitForPayment`'s WebSocket, then go to the node: `http://192.168.1.10:3006`
is read at `http://192.168.1.10:3006/api` and `ws://192.168.1.10:3006/api/v1/ws` (`wss://` for an
`https://` node). BlockCypher and the `MEMPOOL` rate source stay BlockCypher's and mempool.space's.

- **Its URL** follows a rule of its own (SPEC.md §31): `https`, or `http` for a host of a local
  network only (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, a name under `.local`);
  an IPv4 address or a name, never `localhost`, 127.0.0.0/8 nor 0.0.0.0/8; no user name, query nor
  fragment; 200 characters at most. A node the channel names in any other way, or with an invalid
  `fallback` or `payers`, is none: mempool.space is read, and the channel stays valid.
- **Its `fallback`**, when the node does not answer a read (no answer, an error status, a
  redirect, an answer out of format):
  - `"mempool"`: the same read of mempool.space, `NetworkException` when both fail. A node that
    failed less than 60 s ago in the same call (`createInvoice`, `getStatus`, one
    `waitForPayment`) is asked after mempool.space, so that a node that is down does not make each
    read wait for its timeout. The WebSocket goes to mempool.space's at once when the node's fails
    or is lost before any message.
  - `"none"`: mempool.space is never asked. While the node does not answer, no address is leased
    (`createInvoice` gives a Lightning invoice only, or `NetworkException` for an order paid
    on-chain only) and every check of an order with an address throws `NetworkException`: never
    `expired` nor final underpaid for want of an answer. The order waits until the node answers
    again; `waitForPayment` connects to the node's WebSocket alone, again and again.

  A check's two reads (the address's transactions and the chain's height) always come from one
  explorer: when the node fails either, both are asked of mempool.space.

- **A node on the merchant's network** (`http://192.168.1.10:3006`, `http://umbrel.local`) is
  reachable only by a server on that network: a shop the merchant hosts itself, or one that
  joined it (by Tailscale, for one). Any other server fails every read of it, and its fallback
  applies: with `"none"`, no on-chain payment at all.
- **`payers`**: with `true` (only for a node of the Internet: `https` and a name, not under
  `.local`), the checkout links' records name the node (its `url` and `fallback`, never `payers`),
  and their page reads the chain from it too, with the same fallback; the node then sees the
  customer's IP address and the address the page follows, and whoever holds a link reads the
  node's URL. With `false`, only your server reads the node, and the page reads mempool.space.
- **Your `HttpClient` and `WebSocketFactory`** carry the node's requests as every other one: its
  `http://` requests (HTTP/1.1, never an upgrade to HTTP/2) go through the `HttpClient` you give
  the client, its proxy and its rules included, and its `ws://` or `wss://` WebSocket through your
  `WebSocketFactory`, which must open both. An egress proxy or a client that refuses private
  addresses leaves a node on a private address unreachable: its fallback applies.
- **Security**: a node's URL is the merchant's choice, and the package follows a valid one: GET
  requests at the paths of mempool.space's API under `/api`, and the WebSocket's two messages, in
  clear HTTP too to a host of a local network, and to an IPv4 address. Nothing is sent but deposit
  addresses, no redirect is followed, and every answer is bounded and checked. But if your server
  holds the keys of several merchants (a shared host), one merchant could so have it ask machines
  of your own network: restrict what your server can reach to public addresses (see [Outbound
  requests](#outbound-requests)); such a merchant's node is then unreachable, and its fallback
  applies. The node is trusted as mempool.space is: what it shows decides whether an order is
  paid. Over `http`, whoever is on the network between your server and the node can change its
  answers: it is as safe as that network.

## Prices in a currency

Give `fiat` instead of `sats`: the package converts the price to sats on your server, at the rate
of a price source, and declares the price to the merchant's app, which shows it. Nothing to set
up: without a rate source, it asks the default list of sources (below).

```java
Invoice invoice =
    sattleme.createInvoice(
        Order.builder("ORD-1043").fiat("24.50", "CHF").build()); // a string: never a double
// 24.50 CHF at 69 012 CHF/BTC: ⌈2450 × 10^8 / 6 901 200⌉ = 35 502 sats
```

- The currency is an ISO 4217 code in upper case, of the ISO 4217 currencies (List One, without
  funds, metals and test codes). The amount is a string of digits with at most the currency's
  decimals (2 for CHF, 0 for JPY, 3 for KWD): those the merchant's app uses, from the package's own
  table, never from the platform's locale data (`java.util.Currency` differs).
- `Currencies.currencyDigits("CHF")` gives them (SPEC.md §4's `currencyDigits`): `OptionalInt.of(2)`,
  and empty for any text that is not exactly a code of the table (`"chf"`, `" CHF"`, a fund, a
  metal, a test code, `"BTC"`, `""`). It needs no client and asks nothing: ask it whether your
  shop may offer its customers to pay in its currency, and in how many decimals its prices go to
  `fiat(…)`. `Fiat.of("24.50", "CHF")` checks a price as an order's, and `fiat.toSats("69012")`
  converts it at a price of one bitcoin.
- The conversion is exact, on integers (`BigInteger`): the price of one bitcoin is read as the
  decimal its source wrote, and the sats are **rounded up** to the next whole sat, so that the
  merchant never receives less than the price.
- The sats must fit the bounds of the merchant's Lightning address (`merchant()`), as for an
  amount in sats.

### Rate sources

The rate source is optional. Without it, the package asks these sources **one after the other**,
the next one only when the previous one does not answer, gives no usable price, or has no price in
the currency (`RateSource.DEFAULT_LIST`):

`COINGECKO`, then `MEMPOOL`, `COINBASE`, `KRAKEN` and `OPENNODE`.

The dated sources come first (a dated price is checked for its age), then the undated ones. The
first usable price is the rate, and `invoice.rate().source()` names the source that gave it. Each
source that failed before it is reported to your logger as a warning.

To choose, give a source or a list to the client or to an order (an order's wins):

```java
SattlemeClient.builder().key(key).rateSource(RateSource.KRAKEN).build(); // this one only
SattlemeClient.builder().key(key).rateSources(List.of(RateSource.KRAKEN, RateSource.COINGECKO)).build();
Order.builder(ref).fiat("24.50", "CHF").rateSources(List.of(RateSource.MEMPOOL, RateSource.COINBASE)).build();
```

A list holds sources (named or custom), at least one, no name twice; anything else throws
`InvalidOptionException` (`InvalidOrderException` for an order). Each source asked may take up to
`httpTimeoutMs`: a list of 5 sources that all fail to answer may take 5 times as long.

| Source      | Currencies                  | Dated | Notes                                                                                                         |
| ----------- | --------------------------- | ----- | ------------------------------------------------------------------------------------------------------------- |
| `COINGECKO` | many                        | yes   | No key needed. Optional: a Demo key (`coingeckoApiKey`), sent to CoinGecko only. Without one, limited by IP.  |
| `MEMPOOL`   | USD EUR GBP CAD CHF AUD JPY | yes   |                                                                                                               |
| `COINBASE`  | many                        | no    | Prices are decimal strings, read as written.                                                                  |
| `KRAKEN`    | Kraken's pairs              | no    | The last trade's price.                                                                                       |
| `OPENNODE`  | ~140                        | no    |                                                                                                               |
| custom      | yours                       | maybe | `RateSource.custom(currency -> RateQuote.of("69012.5"))`, or with a time. Never a number. Source `"custom"`. |

Any other source (a CoinGecko Pro key, your own mempool instance, several sources checked against
each other) is a custom one. It gives the price as a **string**, as its source wrote it: a number
may already have lost digits.

```java
HttpClient http = HttpClient.newHttpClient();
ObjectMapper json = new ObjectMapper(); // your JSON library: Jackson here
SattlemeClient sattleme =
    SattlemeClient.builder()
        .key(System.getenv("SATTLEME_INTEGRATION_KEY"))
        .rateSource(
            RateSource.custom(
                currency -> {
                  // On the call's thread: an interrupt (a cancelled call) stops it.
                  HttpResponse<String> answer =
                      http.send(
                          HttpRequest.newBuilder(
                                  URI.create("https://rates.example.ch/btc/" + currency))
                              .build(),
                          HttpResponse.BodyHandlers.ofString());
                  if (answer.statusCode() == 404) {
                    throw new UnsupportedCurrencyException(currency, "rates.example.ch");
                  }
                  // Your service answers {"price": "69012.50", "time": 1791378000}: the price as a
                  // string, as it wrote it.
                  JsonNode rate = json.readTree(answer.body());
                  return RateQuote.of(rate.get("price").textValue(), rate.get("time").longValue());
                }))
        .build();
```

A price its source dates is refused when it is more than an hour old, or more than 5 minutes
ahead: the next source of the list is then asked. When no source gives a price, `createInvoice`
throws `UnsupportedCurrencyException` if none of them has the currency, else the last
`RateException` (no usable price) or `NetworkException` (no answer), both retryable. Your source
throws `UnsupportedCurrencyException` when it has no price in a currency, `NetworkException` or
`RateException` when it cannot give one now: the next source is then asked. Any other exception of
yours becomes a `RateException` (yours as its cause); an exception of this package of another kind
stops the list and goes through as it is; an interrupt cancels the call.

## Errors

Every error is a `SattlemeException` (unchecked) with a stable `getCode()` and `isRetryable()`, but
for a cancelled call, which throws `InterruptedException` (or ends its future with a
`CancellationException`). None ever holds a secret: neither the key, nor a sale's key, nor any key
derived from them. That holds of every public method, the helpers too (`RateSource.custom(null)`,
`WebSocketFactory.jdk(null)`: `InvalidOptionException`; `Fiat.toSats` of a `Fiat` that is no price,
made by hand or read from a stored invoice: `InvalidOrderException`, `fiat`), with one exception of
Java's own: the constructors of the data types check nothing, but copy the lists of a status
(`Unconfirmed`, `PaidOnchain`, `PaidMixed`), which throws `NullPointerException` for a null list or
a null in it, as `List.copyOf` does. The future of an asynchronous call fails with the error itself
(`get()` throws it as the cause of an `ExecutionException`).

| Exception                      | Code                   | When                                                                                                                                                       | Retry?           |
| ------------------------------ | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| `InvalidKeyException`          | `invalid_key`          | The key is not an integration key (another prefix, damaged, an old `https://…/pay#…` link).                                                                | no               |
| `InvalidOptionException`       | `invalid_option`       | An option of the client or of `waitForPayment` is invalid: `getParam()` names it.                                                                          | no               |
| `InvalidOrderException`        | `invalid_order`        | A parameter is invalid: `getParam()` names it (`ref`, `sats`, `fiat`, `rateSource`, `durationSeconds`, `note`, `checkout`, `redirectUrl`, `invoice`).      | no               |
| `RevokedException`             | `revoked`              | The merchant removed the integration.                                                                                                                      | no               |
| `ChannelNotFoundException`     | `channel_not_found`    | The relays answered without the integration's channel (`isNewerFormat()`: update the package).                                                             | when not newer   |
| `NetworkException`             | `network`              | A relay, breez.tips, a rate source or the explorer (mempool.space or the node) did not answer, or fewer than a majority of the relays took a record.       | yes              |
| `InvalidInvoiceException`      | `invalid_invoice`      | An invoice or a payment answer failed the checks. Never show such an invoice.                                                                              | no               |
| `InvoicePendingException`      | `invoice_pending`      | An earlier invoice of this order may still be paid, or is being confirmed on-chain: `getInvoice()` (and `getCheckoutUrl()` with `checkout(true)`).         | after it expires |
| `OrderPaidException`           | `order_paid`           | An earlier invoice of this order was paid: `getInvoice()`, `getStatus()`, `getPreimage()` (null on-chain).                                                 | no               |
| `UnsupportedCurrencyException` | `unsupported_currency` | The rate source has no price in this currency (`getCurrency()`).                                                                                           | no               |
| `RateException`                | `rate`                 | No usable price: invalid, too old, or your rate source failed.                                                                                             | yes              |
| `CheckoutUnavailableException` | `checkout_unavailable` | `checkout(true)`, and the merchant's app gives no web app for checkout links.                                                                              | no               |
| `OnchainUnavailableException`  | `onchain_unavailable`  | The order can only be paid on-chain, and no address of the integration's lot could be leased.                                                              | yes              |

## What `createInvoice` does

1. Reads the integration's channel on the Nostr relays of the key (signed by the merchant's team,
   following up to 3 moves of the merchant's relays): the merchant's Lightning address, registry
   and relays, its Bitcoin node if any, or `removed`. At every call: a removal stops the next
   invoice.
2. Checks that no earlier invoice of the order may still be paid (its payment checked: one already
   paid is never handed out again, `OrderPaidException`; one underpaid on-chain gets the invoice of
   its rest, as with `getStatus`, before `InvoicePendingException`). Meanwhile, reads the Lightning
   address and, for a price in a currency, the rate.
3. Converts the price to sats, when it is in a currency; its methods follow from the amount.
4. With Lightning, asks the merchant's Lightning address for the invoice (LUD-06/16), and checks
   it: Bitcoin mainnet, exactly the amount, the merchant's description hash, a verify URL (LUD-21)
   on the same host and port, no longer than asked, dated within 5 minutes.
5. With on-chain, leases an address of the integration's lot: publishes the lease on a majority
   of the merchant's relays, reads the address's leases again on every relay to confirm it, then
   marks it confirmed. A lease that was not confirmed is withdrawn, and the next address is tried;
   an address the explorer (with the node's fallback) shows paid already is skipped. Without an
   address, Lightning only (or `OnchainUnavailableException`; `NetworkException` when the explorer
   does not answer).
6. With `checkout(true)`, publishes its checkout link on a majority of the merchant's relays.
   Without a majority, no invoice (nothing is declared).
7. With Lightning, declares it in the merchant's registry, on a majority of the merchant's relays:
   so that its payment counts for this order's sale and for no other. Without a majority, no
   invoice.

A confirmed lease is never withdrawn, whatever fails after it (its mark, the link, the
declaration): it holds its address until its end, and the order's next `createInvoice` may hand
its invoice out (`InvoicePendingException`).

`getStatus` asks the invoice's verify URL (LUD-21) and accepts a Lightning payment only with the
preimage of its payment hash, in an answer about this very invoice; with an address, it asks the
explorer at the same time and reads the witnesses of the order and the merchant's acceptance (see
[On-chain payments](#on-chain-payments)).

A call opens one connection per relay, used for all it reads and writes there, and closes them
when it is done. Reads and writes go on as soon as a majority of the relays answered (each
declaration is on a majority of them, and any two majorities share a relay): a slow or down relay
slows nothing. Three reads decide that a record is nobody else's and wait for every relay instead,
up to `relayTimeoutMs` after a majority answered: a lease's confirmation, the witnesses read again
after one is published, and the choice among several invoices of an order that may still be paid.
So does the read of the merchant's acceptance, which counts only what a majority of the relays
sent. A relay that failed earlier in the call is not waited for again. A write to the relays past
the majority goes on in the background, for `relayTimeoutMs` at most, then its connection closes.

HTTP requests (the merchant's Bitcoin node's included) follow no redirect, read at most 1 MiB of
an answer's body and 384 KiB of its head (Java's bound, [Requirements](#requirements); a chunked
body's size lines, 2 050 bytes each), and carry `User-Agent: sattleme-Checkout/1`, the same in
every implementation of the specification. The WebSocket handshake (of the relays and of the
explorer) follows no redirect either, carries the same User-Agent (relays behind a bot protection
refuse a handshake without one), and offers no compression; a message larger than 1 MiB closes
the connection. Of what a relay sends, a read keeps only what opens with
its keys and, for a signed record, whose signature checks out, once per event and without the
members a relay may add to it (of a lease, only its id, author, date, signature and end; of any
other event, its id and its date): a relay cannot make the server's memory grow.

## Outbound requests

Besides fixed hosts (the rate sources, mempool.space, BlockCypher), your server asks hosts that
others choose: the merchant's Lightning address and its provider (breez.tips), the merchant's
relays, and the merchant's Bitcoin node when its app names one. Their URLs are checked first:
`https` or `wss` only, no IP address, no `localhost` nor a name under it, no redirect followed;
but for the node, whose URL allows `http` to a host of a local network and an IPv4 address ([The
merchant's Bitcoin node](#the-merchants-bitcoin-node)), never `localhost`. A name may still lead
into your own network (an internal name, a public name resolving to a private address), and the
package does not resolve names itself (a name that resolves again would bypass such a check). If
your server holds the keys of several merchants, or sits in a network with services of its own,
restrict what it can reach to public addresses: in your network (an egress proxy, a firewall), or
in the `HttpClient` and `WebSocketFactory` you give the client (an `HttpClient` with a
`ProxySelector` to your egress proxy serves both; a merchant's node on a private address is then
unreachable, and its fallback applies).

## Limits

- One hour at most per invoice; on-chain, about 30 minutes more to be `paid` (3 confirmations).
- Not measured: the limits of breez.tips, of the explorer and of the relays for a shop that makes
  many invoices from one IP address. BlockCypher takes 100 requests an hour from an IP address
  without a token: it is asked only about a transaction no check saw while its lease ran.

Not in this version: refunds, webhooks, and showing open (unpaid) invoices in the merchant's app.

## Security

Report a vulnerability privately: [SECURITY.md](SECURITY.md).

**Cryptography.** secp256k1 is Bitcoin Core's libsecp256k1 0.8.0, the commit secp256k1-kmp 0.24.0
builds (its `native/secp256k1`, 6e2c8bc), through ACINQ's JNI bindings
([Dependencies](#dependencies)); ChaCha20, HMAC-SHA256 and SHA-256 are the JDK's, and random bytes
come from `SecureRandom`.

- **In constant time**: the public key of a secret (`secp256k1_ec_pubkey_create`) and BIP-340
  signatures (`secp256k1_schnorrsig_sign`), which multiply the generator by the secret with
  `secp256k1_ecmult_gen`, libsecp256k1's constant-time multiplication (no branch nor memory access
  that depends on the secret; its `ctime_tests` check it); and the MACs of NIP-44, compared with
  `MessageDigest.isEqual` (SPEC.md §27).
- **Not in constant time**: the shared point of NIP-44 (its ECDH). libsecp256k1's constant-time
  ECDH, `secp256k1_ecdh` (over `secp256k1_ecmult_const`), gives only the SHA-256 of the point, and
  secp256k1-kmp offers no other: NIP-44 needs the point's x unhashed. The package computes it with
  `secp256k1_ec_pubkey_tweak_mul`, whose `secp256k1_ecmult` takes a time that depends on the
  secret scalar. That scalar is an order's sale secret (derived from the key and the order's
  reference), multiplied by the merchant's reading key when the order's declarations and leases are
  sealed or opened: whoever could time those computations on your server closely could learn
  something of that order's sale secret, which signs that order's records; sealing a record for the
  merchant's registry also takes the registry key, which only the integration key reads, and the
  integration key itself is never multiplied so.
- Signatures are verified, and public keys checked, in variable time: they are public.

**The merchant's Bitcoin node.** Its URL is the merchant's choice, and it may name a host of a
local network in plain `http`, or an IPv4 address: at a shared host, one merchant could have your
server ask machines of your own network (GET requests under `/api` and two WebSocket messages
only, nothing sent but deposit addresses). Restrict what your server can reach to public addresses
if it holds the keys of several merchants ([The merchant's Bitcoin
node](#the-merchants-bitcoin-node), [Outbound requests](#outbound-requests)).

## Options

```java
SattlemeClient sattleme =
    SattlemeClient.builder()
        .key(key) // required
        .rateSource(source) // optional: a source, or .rateSources(list); the default list
        .coingeckoApiKey(demoKey) // optional: CoinGecko's Demo key (visible ASCII), sent to CoinGecko only
        .httpClient(httpClient) // an HttpClient that follows no redirect (HttpClient.Redirect.NEVER)
        .webSocketFactory(factory) // the relays' and the explorer's WebSockets (wss://, a node's ws://): WebSocketFactory.jdk(httpClient)
        .relayTimeoutMs(10_000) // per relay request, connection included: 100 to 60 000
        .httpTimeoutMs(10_000) // per HTTP request, body included: 100 to 60 000
        .logger(System.getLogger("me.sattle.checkout")) // warnings only, never a secret
        .build();
```

An invalid option throws `InvalidOptionException` from `build()`, before any request. The clock
and the randomness (`clock(Clock)`, `random(SecureRandom)`) are for tests only.

By default the client makes its own `HttpClient` (no redirect, the HTTP timeout to connect) and
opens WebSockets with Java's own (`java.net.http.WebSocket`, which offers no compression), with a
handshake bounded by the relay timeout and a message bounded at 1 MiB. Your own `WebSocketFactory`
should keep those bounds and send the headers it is given (the User-Agent).

## Dependencies

| Artifact                                  | Version | Why                                                                                                                                                                                                                                                                           |
| ----------------------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fr.acinq.secp256k1:secp256k1-kmp-jvm`    | 0.24.0  | secp256k1: BIP-340 Schnorr signatures (with 32 fresh random bytes of auxiliary data) and their verification, x-only keys, `lift_x`, the shared point of NIP-44. ACINQ's bindings to Bitcoin Core's libsecp256k1 (audited, the reference of BIP-340), maintained for its wallets. |
| `fr.acinq.secp256k1:secp256k1-kmp-jni-jvm` | 0.24.0  | Their JNI implementation, at run time, with libsecp256k1 built for Linux (x86-64, arm64), macOS (universal) and Windows (x86-64): extracted to a temporary directory at first use (`fr.acinq.secp256k1.tmpdir`, else `java.io.tmpdir`).                                     |
| `org.jetbrains.kotlin:kotlin-stdlib`      | 2.4.21  | Kotlin's standard library, which secp256k1-kmp (written in Kotlin) needs: its latest (secp256k1-kmp names 2.3.10; Kotlin's library reads older code), and `org.jetbrains:annotations` 26.1.0, its own (annotations only).                                                     |

Java has no secp256k1 of its own (the JDK removed its curve in Java 16), and no BIP-340.
Everything else is the JDK's: HTTP and WebSockets (`java.net.http`), SHA-256 and HMAC
(`MessageDigest`, `Mac`), ChaCha20 for NIP-44 v2 (`Cipher` "ChaCha20", RFC 8439, checked against
NIP-44's official vectors), the CSPRNG (`SecureRandom`), `BigInteger`, strict UTF-8
(`CharsetDecoder`). HKDF (RFC 5869) is a few lines over the JDK's HMAC. Every JSON the package
reads (an HTTP answer, a relay's message, a record, a stored invoice, the explorer's WebSocket)
goes through its own reader, as JavaScript's `JSON.parse` reads it (SPEC.md §2: the last member of
a name counts, a number of any length is a double, a lone surrogate is kept, nothing but RFC 8259)
and no more than 64 arrays and objects nested: no Java JSON library reads it so as it is. The tests
use JUnit Jupiter 6.1.3, and Jackson 2.23.0 and Gson 2.15.0 to check that neither writes a key.

The build: Maven 3.10.1 through the Maven Wrapper (its SHA-256 checked), and its plugins, each
pinned: the compiler 3.16.0 (javac with every lint warning an error, for Java 17), Surefire 3.6.1,
the jar 3.5.1, sources 3.4.0 and javadoc 3.12.0 plugins (javadoc's lint too), the Enforcer 3.7.0
(Maven and Java versions, no version range, one version of each dependency), Spotless 3.11.0 with
google-java-format 2.0.0 (the format), the clean, resources, install, deploy and site plugins
Maven's lifecycle names, and for releases GPG 3.2.8.

Every version is pinned exactly in `pom.xml`, and Maven checks the SHA-256 of every artifact it
resolves, dependencies and plugins, against `.mvn/checksums/checksums-central.sha256`
(`.mvn/maven.config`: its resolver's trusted checksums): Maven has no lockfile, and this one says
which bytes each version is.

## Other languages

The package's public API is the package `me.sattle.checkout`: `SattlemeClient`, its builder, the
data model and the exceptions; everything else (`me.sattle.checkout.internal…`, not exported by the
module) is internal. Implementations in other languages follow
[`SPEC.md`](https://github.com/sattleme/checkout-spec/blob/main/SPEC.md) and pass its conformance
vectors; the invoices they store are the same JSON.

## Development

```sh
scripts/check # the pinned specification, the build (checksums, javac lint, the format), the tests, the jars
```

It needs a JDK 21 or later (the format check, google-java-format, runs there only; on 17, the rest
runs). The Maven Wrapper (`./mvnw`) downloads Maven 3.10.1 and checks its SHA-256. `scripts/check
<JDK home>…` runs the build and the tests again on each JDK given, as the CI does on 17 and 25. The
tests run local Nostr relays and fakes of breez.tips, of the rate sources, of the explorers
(mempool.space and a merchant's Bitcoin node, REST and WebSocket) and of BlockCypher: no network. One test class or method:

```sh
./mvnw test -Dtest='RestTest*' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw spotless:apply # the format, on JDK 21 or later
```

`spec/` is a copy of [sattleme/checkout-spec](https://github.com/sattleme/checkout-spec)
pinned to one commit (`spec/PIN.json`, checked file by file by `scripts/spec check` and by the
tests), never edited here. Its vectors are what the tests check the package against: the
conformance vectors, the merchant's app's vectors (`spec/vectors/app/app.json`) and the vectors an
implementation makes for the app to read (`spec/vectors/app/implementation-declaration.json`,
`spec/vectors/app/checkout-link.json`, `spec/vectors/app/checkout-rest.json`). To take a new
version of the specification: clone checkout-spec, check out that version's commit, run
`scripts/spec import <clone>` (it replaces `spec/` and writes `PIN.json`), then make the code pass
the new vectors, in one pull request.

To change a dependency or a plugin: its version in `pom.xml`, then `scripts/checksums` (on JDK 21
or later), which records the SHA-256 of every artifact again from a fresh local repository; review
`git diff .mvn/checksums` before committing it. A Dependabot pull request fails the build until
then.

## Releasing

Only a maintainer releases. A release is a tag `vX.Y.Z` on a commit of `main`, equal to the
`<version>` of `pom.xml`, with its section in `CHANGELOG.md`. On that tag,
`.github/workflows/release.yml` runs the CI again, checks the tag against the version, against
`main` and against the changelog, then, in a job bound to the protected environment `release` (a
maintainer approves each release), which alone holds the signing key:

1. builds `me.sattle:checkout` and signs its jar, sources, javadoc and POM (GPG, `-P release`);
2. checks each signature against `signing-key.asc` (`scripts/maven-repo stage`);
3. publishes them in the GitHub Release of the tag, with its changelog section as notes: once
   published, neither its files nor its tag can change (release immutability).

Then it makes the Maven repository again from every published release, each signature checked
again (`scripts/maven-repo site`), and deploys it to GitHub Pages at `https://maven.sattle.me`.
Should a deployment fail, re-run that job of the latest release: the repository is made from the
releases alone.

What the maintainers set up once, before the first release:

- the signing key, its primary key signing (no signing subkey), its public key in
  `signing-key.asc` (its fingerprint in `scripts/maven-repo`) and on keyserver.ubuntu.com;
- on GitHub: the environment `release` (required reviewers, deployment tags `v*.*.*`) and its
  secrets `MAVEN_GPG_KEY` (the armored private key) and `MAVEN_GPG_PASSPHRASE`; GitHub Pages
  published by GitHub Actions, its custom domain `maven.sattle.me` (verified, HTTPS enforced),
  and the environment `github-pages` allowing the tags `v*.*.*`;
- on the DNS of sattle.me: `maven.sattle.me`, a CNAME to `sattleme.github.io`.

## License

[MIT](LICENSE)
