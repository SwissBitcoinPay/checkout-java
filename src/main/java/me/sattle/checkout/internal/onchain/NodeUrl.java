package me.sattle.checkout.internal.onchain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.sattle.checkout.internal.Constants;

/**
 * A node URL (SPEC.md §31, "A node URL"), checked, and where the node is read: the URL of the
 * merchant's Bitcoin node, a node of its own that serves mempool.space's API (an Umbrel, a server
 * of its own). It follows a rule of its own, not the https URL's (SPEC.md §15): {@code http} is
 * allowed for a host of a local network, and so is an IPv4 address. Nothing is repaired: a text
 * that is not in its one spelling is no node URL. The package asks plain {@code http} of a URL
 * built from a node URL alone (the explorer's reads of the node, {@link Explorer}).
 *
 * @param url the node URL, as written
 * @param rest {@code <url>/api}: the {@code <explorer>} of SPEC.md §31, "Explorers"
 * @param webSocket {@code <url>} with {@code ws} for its leading {@code http}, then {@code
 *     /api/v1/ws}
 * @param local whether its host is of a local network: an IPv4 address of 10.0.0.0/8,
 *     172.16.0.0/12, 192.168.0.0/16 or 100.64.0.0/10, or a name whose last label is {@code local}
 * @param internet whether it is a node of the Internet: {@code https}, and a name (never an IPv4
 *     address) whose last label is not {@code local}
 */
public record NodeUrl(String url, String rest, String webSocket, boolean local, boolean internet) {
  /**
   * {@code <scheme>://<host>[:<port>][<path>]}: the host up to {@code :} or {@code /} (nothing that
   * may start a user name, a query or a fragment, no whitespace), a port of 1 to 5 digits without a
   * leading zero, a path of visible ASCII. The parts are then checked one by one. Matched against a
   * text of 200 characters at most (its length checked first).
   */
  private static final Pattern NODE_URL =
      Pattern.compile(
          "(https?)://([^/:?#@\\\\\\s]+)(?::([1-9][0-9]{0,4}))?(/[\\x21-\\x7e]*)?", Pattern.DOTALL);

  /** An IPv4 address: 4 numbers from 0 to 255, none with a leading zero. */
  private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";

  private static final Pattern IPV4 =
      Pattern.compile(OCTET + "\\." + OCTET + "\\." + OCTET + "\\." + OCTET);

  /**
   * A label of a name: 1 to 63 of {@code a–z 0–9 -}, neither starting nor ending with {@code -}.
   */
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** The longest name. */
  private static final int MAX_NAME_LENGTH = 253;

  /** The ending of a path that names the node's API, not the node: {@code /api}, any case. */
  private static final Pattern API_PATH = Pattern.compile(".*/api", Pattern.CASE_INSENSITIVE);

  /**
   * {@code value} as a node URL (SPEC.md §31, "A node URL"), with where the node is read: {@code
   * https}, or {@code http} for a local host; a host in lowercase, an IPv4 address (never in
   * 0.0.0.0/8 nor 127.0.0.0/8) or a name of 2 labels or more (never {@code localhost} nor under
   * it); a port from 1 to 65535; a path that ends neither with {@code /} nor with {@code /api}; 200
   * characters at most. So no user name nor password, no query, no fragment, no IPv6 address, no
   * whitespace.
   *
   * @param value a text, or null
   * @return the node URL, or null when it is not one
   */
  public static NodeUrl parse(String value) {
    if (value == null || value.length() > Constants.MAX_NODE_URL_LENGTH) return null;
    Matcher match = NODE_URL.matcher(value);
    if (!match.matches()) return null;
    String scheme = match.group(1);
    String host = match.group(2);
    String port = match.group(3);
    String path = match.group(4) == null ? "" : match.group(4);
    if (port != null && Integer.parseInt(port) > 65_535) return null;
    if (!isNodePath(path)) return null;
    int[] ipv4 = ipv4Of(host);
    boolean local;
    if (ipv4 != null) {
      // Never the machine itself, nor "this network".
      if (ipv4[0] == 0 || ipv4[0] == 127) return null;
      local = isLocalIpv4(ipv4);
    } else {
      if (!isName(host)) return null;
      local = host.endsWith(".local");
    }
    if (scheme.equals("http") && !local) return null;
    return new NodeUrl(
        value,
        value + "/api",
        "ws" + value.substring("http".length()) + "/api/v1/ws",
        local,
        scheme.equals("https") && ipv4 == null && !local);
  }

  /** The four numbers of an IPv4 address, or null when {@code host} is not one. */
  private static int[] ipv4Of(String host) {
    Matcher match = IPV4.matcher(host);
    if (!match.matches()) return null;
    int[] octets = new int[4];
    for (int i = 0; i < 4; i += 1) octets[i] = Integer.parseInt(match.group(i + 1));
    return octets;
  }

  /** Whether an IPv4 address is of a local range: 10/8, 172.16/12, 192.168/16, 100.64/10. */
  private static boolean isLocalIpv4(int[] octets) {
    int a = octets[0];
    int b = octets[1];
    return a == 10
        || (a == 172 && b >= 16 && b <= 31)
        || (a == 192 && b == 168)
        || (a == 100 && b >= 64 && b <= 127);
  }

  /**
   * Whether {@code host} is a name: 253 characters at most, 2 labels or more, the last starting
   * with a letter, never {@code localhost} nor a name under it.
   */
  private static boolean isName(String host) {
    if (host.length() > MAX_NAME_LENGTH) return false;
    String[] labels = host.split("\\.", -1);
    if (labels.length < 2) return false;
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) return false;
    }
    String last = labels[labels.length - 1];
    char first = last.charAt(0);
    return first >= 'a' && first <= 'z' && !last.equals("localhost");
  }

  /** Whether {@code path} (after the authority, empty when absent) is a node URL's. */
  private static boolean isNodePath(String path) {
    if (path.isEmpty()) return true;
    // Visible ASCII already; never ?, #, @ nor \.
    for (int i = 0; i < path.length(); i += 1) {
      if ("?#@\\".indexOf(path.charAt(i)) >= 0) return false;
    }
    // The node's URL, not its API's: not / alone, no trailing /, no /api at the end.
    return !path.endsWith("/") && !API_PATH.matcher(path).matches();
  }
}
