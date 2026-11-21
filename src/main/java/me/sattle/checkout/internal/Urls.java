package me.sattle.checkout.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts and URLs in their one spelling (SPEC.md §9, §15, §16, §30): what the package asks over the
 * network (relays, the Lightning address's pay URL, its callback and verify URLs) and what a
 * checkout link sends the customer to. Nothing is repaired: a URL that is not so is refused, so
 * that no request goes to an IP address, to the machine itself ({@code localhost} and the names
 * under it, RFC 6761) or to a host other than the one checked. Every pattern here matches a whole
 * text ({@link Matcher#matches()}): Java's {@code $} also matches before a final line terminator.
 * Java's matcher recurses once per repetition of a group: a group is repeated only over a text
 * whose length was bounded first, a URL's characters are checked by hand.
 */
public final class Urls {
  private Urls() {}

  /**
   * A host: labels of {@code a–z 0–9 -} separated by {@code .}, the last of two letters at least.
   */
  private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}");

  private static final Pattern LOCALHOST = Pattern.compile("(?:.*\\.)?localhost");

  /**
   * A relay address after {@code wss://}: a host, an optional port, an optional path without
   * ECMAScript's whitespace (spelled out: the merchant's app checks with JavaScript's {@code \s}),
   * {@code ?}, {@code #} and {@code ,}.
   */
  private static final Pattern RELAY =
      Pattern.compile(
          "([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,})(:[0-9]{1,5})?"
              + "(/[^\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f"
              + "\\u205f\\u3000\\ufeff?#,]*)?");

  /** RFC 3986's characters of a URL, but {@code %}. */
  private static final String URL_CHARACTERS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=";

  /**
   * Whether {@code value} holds the characters of a redirect URL and of an https URL only, one at
   * least: RFC 3986's, and {@code %} with two hex digits.
   *
   * @param value the text
   * @return true when it does
   */
  public static boolean isUrlText(String value) {
    int length = value.length();
    if (length == 0) return false;
    for (int i = 0; i < length; i += 1) {
      char c = value.charAt(i);
      if (c == '%') {
        if (i + 2 >= length
            || !isHexDigit(value.charAt(i + 1))
            || !isHexDigit(value.charAt(i + 2))) {
          return false;
        }
        i += 2;
      } else if (URL_CHARACTERS.indexOf(c) < 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /**
   * {@code https://}, the authority (up to the first {@code /}, {@code ?} or {@code #}), anything.
   */
  private static final Pattern HTTPS =
      Pattern.compile("https://([^/?#]*)(?:[/?#].*)?", Pattern.DOTALL);

  /** An authority: a host and an optional port of 1 to 5 digits. */
  private static final Pattern AUTHORITY = Pattern.compile("([^:]*)(?::([0-9]{1,5}))?");

  /** A Lightning address, its ASCII whitespace removed and its ASCII letters lowered (LUD-16). */
  private static final Pattern LIGHTNING_ADDRESS =
      Pattern.compile("([a-z0-9\\-_.+]+)@([a-z0-9.-]+)");

  /** Its domain: labels of {@code a–z 0–9 -} separated by {@code .}, two at least. */
  private static final Pattern DOMAIN = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)+");

  /**
   * Whether {@code host} is a host as in a relay address: lower case, at most 253 characters (so
   * never an IP address), its last label not {@code localhost}.
   *
   * @param host the host
   * @return true when it is one
   */
  public static boolean isHost(String host) {
    return host.length() <= Constants.MAX_HOST_LENGTH
        && HOST.matcher(host).matches()
        && !LOCALHOST.matcher(host).matches();
  }

  /**
   * Whether {@code port} (its digits, null when the URL has none) is absent or 1 to 65535.
   *
   * @param port the digits, or null
   * @return true when valid
   */
  public static boolean isPort(String port) {
    if (port == null) return true;
    int value = Integer.parseInt(port);
    return value >= 1 && value <= 65535;
  }

  /**
   * Whether {@code url} is a relay address in its one spelling (SPEC.md §9): {@code wss://}, a
   * host, an optional port, an optional path without a trailing slash nor a lone surrogate, 120
   * code points at most.
   *
   * @param url the address
   * @return true when it is one
   */
  public static boolean isRelayUrl(String url) {
    // Bounded first: the pattern repeats a group per label.
    if (!url.startsWith("wss://") || Text.codePointLength(url) > Constants.MAX_RELAY_URL_LENGTH) {
      return false;
    }
    Matcher match = RELAY.matcher(url.substring(6));
    if (!match.matches()) return false;
    String port = match.group(2);
    String path = match.group(3) == null ? "" : match.group(3);
    return isHost(match.group(1))
        && isPort(port == null ? null : port.substring(1))
        && !path.endsWith("/")
        && Text.isWellFormed(path);
  }

  /**
   * An https URL's host and port.
   *
   * @param host its host
   * @param port its port, 443 when it names none
   */
  public record HttpsUrl(String host, int port) {
    /**
     * Whether two https URLs are on the same host: their hosts and their ports are equal.
     *
     * @param other the other URL
     * @return true when on the same host
     */
    public boolean sameHost(HttpsUrl other) {
      return host.equals(other.host) && port == other.port;
    }
  }

  /**
   * {@code value} as an https URL (SPEC.md §15: rule 1 of a redirect URL): 1 to 2048 characters of
   * a redirect URL, {@code https://}, a host, an optional port, then nothing or {@code /}, {@code
   * ?} or {@code #} and anything.
   *
   * @param value a text, or null
   * @return its host and port, or null when it is not one
   */
  public static HttpsUrl parseHttpsUrl(String value) {
    if (value == null || value.length() > Constants.MAX_HTTPS_URL_LENGTH || !isUrlText(value)) {
      return null;
    }
    Matcher https = HTTPS.matcher(value);
    if (!https.matches()) return null;
    Matcher authority = AUTHORITY.matcher(https.group(1));
    if (!authority.matches() || !isHost(authority.group(1)) || !isPort(authority.group(2))) {
      return null;
    }
    return new HttpsUrl(
        authority.group(1),
        authority.group(2) == null ? 443 : Integer.parseInt(authority.group(2)));
  }

  /**
   * The LNURL-pay endpoint of a Lightning address (LUD-16): ASCII whitespace around it removed,
   * ASCII letters lowered, {@code https://<domain>/.well-known/lnurlp/<name>}, which must be an
   * https URL (SPEC.md §16).
   *
   * @param address the Lightning address
   * @return its pay URL, or null when it is not one
   */
  public static String lightningAddressUrl(String address) {
    Matcher match = LIGHTNING_ADDRESS.matcher(Text.asciiLower(Text.trimAscii(address)));
    if (!match.matches()) return null;
    String domain = match.group(2);
    // Its URL's host is 253 characters at most: bounded before the pattern of its labels.
    if (domain.length() > Constants.MAX_HOST_LENGTH || !DOMAIN.matcher(domain).matches()) {
      return null;
    }
    String url = "https://" + domain + "/.well-known/lnurlp/" + match.group(1);
    return parseHttpsUrl(url) == null ? null : url;
  }

  /**
   * The host of an https URL, for messages: never its path (a Lightning address's name is in it).
   *
   * @param url a URL the package asked
   * @return its host and port as written
   */
  public static String hostOf(String url) {
    int start = url.indexOf("://");
    if (start < 0) return "(invalid URL)";
    int end = start + 3;
    while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end += 1;
    return url.substring(start + 3, end);
  }

  /** What {@link URI} takes in a path besides letters, digits and escapes (RFC 2396). */
  private static final String URI_PATH = "-_.!~*'(),;:$&+=@/";

  /** What it takes in a query besides those: the rest of RFC 2396's reserved characters. */
  private static final String URI_QUERY = URI_PATH + "?[]";

  /**
   * The URI the platform's HTTP client asks for an https URL (checked, SPEC.md §15), as {@code
   * fetch} asks it: its fragment (from its first {@code #}) dropped, which is never sent; then each
   * character that {@link URI} refuses where it is (a {@code [} or {@code ]} in the path, anything
   * not of RFC 3986) percent-encoded, its UTF-8 bytes; everything else, escapes included, as it is.
   *
   * @param url an https URL, or any URL the package asks
   * @return its URI
   * @throws IllegalArgumentException when it is no URI even so (no scheme, no host)
   */
  public static URI requestUri(String url) {
    int hash = url.indexOf('#');
    String sent = hash < 0 ? url : url.substring(0, hash);
    int scheme = sent.indexOf("://");
    if (scheme < 0) return URI.create(sent);
    int start = scheme + 3;
    while (start < sent.length() && "/?".indexOf(sent.charAt(start)) < 0) start += 1;
    StringBuilder out = new StringBuilder(sent.substring(0, start));
    boolean query = false;
    for (int i = start; i < sent.length(); ) {
      int code = sent.codePointAt(i);
      int width = Character.charCount(code);
      if (code == '?' && !query) {
        query = true;
        out.append('?');
        i += 1;
        continue;
      }
      boolean escape =
          code == '%'
              && i + 2 < sent.length()
              && isHexDigit(sent.charAt(i + 1))
              && isHexDigit(sent.charAt(i + 2));
      if (escape) {
        out.append(sent, i, i + 3);
        i += 3;
        continue;
      }
      boolean plain =
          code < 0x80
              && ((code >= 'a' && code <= 'z')
                  || (code >= 'A' && code <= 'Z')
                  || (code >= '0' && code <= '9')
                  || (query ? URI_QUERY : URI_PATH).indexOf(code) >= 0);
      if (plain) {
        out.appendCodePoint(code);
      } else {
        for (byte b : new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8)) {
          out.append('%').append(String.format("%02X", b & 0xff));
        }
      }
      i += width;
    }
    return URI.create(out.toString());
  }

  /**
   * The URI the platform's WebSocket connects to for a relay address (checked): its path
   * percent-encoded where {@link URI} refuses a character (any non-ASCII one in UTF-8, as WHATWG's
   * URL parser writes it, and those RFC 2396 does not take), its escapes kept.
   *
   * @param url a relay address
   * @return its URI
   */
  public static URI webSocketUri(String url) {
    int pathStart = url.indexOf('/', "wss://".length());
    if (pathStart < 0) return URI.create(url);
    StringBuilder out = new StringBuilder(url.substring(0, pathStart));
    String path = url.substring(pathStart);
    for (int i = 0; i < path.length(); ) {
      int code = path.codePointAt(i);
      int width = Character.charCount(code);
      boolean escape =
          code == '%'
              && i + 2 < path.length()
              && Character.digit(path.charAt(i + 1), 16) >= 0
              && path.charAt(i + 1) < 0x80
              && Character.digit(path.charAt(i + 2), 16) >= 0
              && path.charAt(i + 2) < 0x80;
      if (escape) {
        out.append(path, i, i + 3);
        i += 3;
        continue;
      }
      boolean plain =
          code < 0x80
              && (Character.isLetterOrDigit(code) || "-_.!~*'(),;:$&+=@/".indexOf(code) >= 0);
      if (plain) {
        out.appendCodePoint(code);
      } else {
        for (byte b : new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8)) {
          out.append('%').append(String.format("%02X", b & 0xff));
        }
      }
      i += width;
    }
    return URI.create(out.toString());
  }
}
