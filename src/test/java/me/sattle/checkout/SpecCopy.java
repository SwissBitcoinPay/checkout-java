package me.sattle.checkout;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pinned copy of the specification (sattleme/checkout-spec, its PORTING.md: "The pinned copy of
 * the specification"): {@code spec/} holds the files its {@code manifest.json} lists, unchanged,
 * and {@code spec/PIN.json} says which commit they come from, with the SHA-256 of each.
 *
 * <pre>
 *   scripts/spec check
 *       Whether spec/ is PIN.json's, file for file (SpecPinTest runs it too).
 *   scripts/spec import &lt;checkout-spec clone&gt;
 *       Replaces spec/ with the files of a clean clone, at the commit it is on, and writes
 *       PIN.json. Its manifest.json must be its files'. Clone and check out the version yourself:
 *       no network.
 * </pre>
 *
 * <p>A program of one file that needs nothing but the JDK ({@code java SpecCopy.java …}), run by
 * {@code scripts/spec}; the tests call {@link #check(Path)}.
 */
public final class SpecCopy {
  private SpecCopy() {}

  /** The repository the copy comes from. */
  public static final String REPOSITORY = "https://github.com/sattleme/checkout-spec";

  private static final String PIN = "PIN.json";

  /**
   * {@code scripts/spec check | import <clone>}.
   *
   * @param args the command
   * @throws IOException when a file cannot be read or written
   * @throws InterruptedException when interrupted while running git
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    Path root = Paths.get("").toAbsolutePath();
    Path copy = root.resolve("spec");
    if (args.length == 1 && args[0].equals("check")) {
      List<String> problems = check(copy);
      for (String problem : problems) System.err.println(problem);
      System.exit(problems.isEmpty() ? 0 : 1);
    } else if (args.length == 2 && args[0].equals("import")) {
      Map<String, Object> pin = importSpec(Paths.get(args[1]).toAbsolutePath(), copy);
      System.out.println(
          "spec/ is checkout-spec " + pin.get("version") + " at " + pin.get("commit"));
    } else {
      System.err.println("Usage: scripts/spec check | import <checkout-spec clone>");
      System.exit(2);
    }
  }

  /**
   * What differs between {@code copy} and its PIN.json.
   *
   * @param copy the directory of the copy
   * @return the problems, empty when the copy is the pinned one
   * @throws IOException when a file cannot be read
   */
  public static List<String> check(Path copy) throws IOException {
    List<String> problems = new ArrayList<>();
    Path pinPath = copy.resolve(PIN);
    if (!Files.isRegularFile(pinPath)) return List.of(PIN + " is missing");
    Map<String, Object> pin = object(new Reader(Files.readString(pinPath)).document());
    if (!REPOSITORY.equals(pin.get("repository"))) {
      problems.add(PIN + ": repository is not " + REPOSITORY);
    }
    Object commit = pin.get("commit");
    if (!(commit instanceof String) || !Pattern.matches("[0-9a-f]{40}", (String) commit)) {
      problems.add(PIN + ": commit is not a commit");
    }
    Map<String, Object> files = object(pin.getOrDefault("files", Map.of()));
    for (Map.Entry<String, Object> file : files.entrySet()) {
      Path path = copy.resolve(file.getKey());
      if (!Files.isRegularFile(path)) {
        problems.add(file.getKey() + " is missing");
      } else if (!sha256(path).equals(file.getValue())) {
        problems.add(file.getKey() + " differs from " + PIN);
      }
    }
    for (String file : filesUnder(copy)) {
      if (!file.equals(PIN) && !files.containsKey(file)) {
        problems.add(file + " is not in " + PIN);
      }
    }
    return problems;
  }

  /**
   * Replaces {@code copy} with the files of the clean clone {@code clone}, at its commit, and
   * writes PIN.json.
   *
   * @param clone a clean clone of checkout-spec, on the commit to pin
   * @param copy the directory of the copy
   * @return the pin written
   * @throws IOException when a file cannot be read or written, or the clone is not clean
   * @throws InterruptedException when interrupted while running git
   */
  public static Map<String, Object> importSpec(Path clone, Path copy)
      throws IOException, InterruptedException {
    if (!git(clone, "status", "--porcelain").isEmpty()) {
      throw new IOException(clone + " is not clean");
    }
    String commit = git(clone, "rev-parse", "HEAD");
    Map<String, Object> manifest =
        object(new Reader(Files.readString(clone.resolve("manifest.json"))).document());
    Map<String, Object> files = object(manifest.get("files"));
    for (Map.Entry<String, Object> file : files.entrySet()) {
      if (!sha256(clone.resolve(file.getKey())).equals(file.getValue())) {
        throw new IOException(file.getKey() + " is not the manifest's");
      }
    }
    if (Files.exists(copy)) {
      try (Stream<Path> walk = Files.walk(copy)) {
        for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.delete(path);
      }
    }
    for (String file : files.keySet()) {
      Path target = copy.resolve(file);
      Files.createDirectories(target.getParent());
      Files.copy(clone.resolve(file), target);
    }
    Map<String, Object> pin = new LinkedHashMap<>();
    pin.put("repository", REPOSITORY);
    pin.put("commit", commit);
    pin.put("version", manifest.get("version"));
    pin.put("protocol", manifest.get("protocol"));
    pin.put("files", files);
    Files.writeString(copy.resolve(PIN), write(pin, "") + "\n", StandardCharsets.UTF_8);
    return pin;
  }

  /** The output of {@code git -C clone args}, trimmed. */
  private static String git(Path clone, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", clone.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out;
    try (InputStream stream = process.getInputStream()) {
      out = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + out);
    }
    return out;
  }

  /** The lowercase hex SHA-256 of a file's bytes. */
  private static String sha256(Path path) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Every file under {@code dir}, as sorted paths relative to it with "/". */
  private static List<String> filesUnder(Path dir) throws IOException {
    TreeSet<String> found = new TreeSet<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile)
          .forEach(path -> found.add(dir.relativize(path).toString().replace('\\', '/')));
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    return new ArrayList<>(found);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) throws IOException {
    if (!(value instanceof Map)) throw new IOException("Not a JSON object");
    return (Map<String, Object>) value;
  }

  /** {@code value} as {@code JSON.stringify(value, null, 2)} writes it (objects and strings). */
  private static String write(Object value, String indent) {
    if (value instanceof Map) {
      StringBuilder out = new StringBuilder("{\n");
      String inner = indent + "  ";
      boolean first = true;
      for (Map.Entry<?, ?> member : ((Map<?, ?>) value).entrySet()) {
        if (!first) out.append(",\n");
        first = false;
        out.append(inner)
            .append(quote((String) member.getKey()))
            .append(": ")
            .append(write(member.getValue(), inner));
      }
      return out.append("\n").append(indent).append("}").toString();
    }
    if (value instanceof String) return quote((String) value);
    if (value instanceof Double && ((Double) value) == Math.rint((Double) value)) {
      return Long.toString(((Double) value).longValue());
    }
    return String.valueOf(value);
  }

  private static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    return out.append('"').toString();
  }

  /** A small reader of the JSON of manifest.json and PIN.json: objects keep their order. */
  private static final class Reader {
    private final String text;
    private int at;

    Reader(String text) {
      this.text = text;
    }

    Object document() throws IOException {
      Object value = value();
      space();
      if (at != text.length()) throw new IOException("Not JSON: text after the value");
      return value;
    }

    private Object value() throws IOException {
      space();
      if (at >= text.length()) throw new IOException("Not JSON: unexpected end");
      char c = text.charAt(at);
      if (c == '{') {
        at += 1;
        Map<String, Object> object = new LinkedHashMap<>();
        space();
        if (peek() == '}') {
          at += 1;
          return object;
        }
        for (; ; ) {
          space();
          String name = string();
          space();
          expect(':');
          object.put(name, value());
          space();
          if (peek() == ',') {
            at += 1;
          } else {
            expect('}');
            return object;
          }
        }
      }
      if (c == '[') {
        at += 1;
        List<Object> array = new ArrayList<>();
        space();
        if (peek() == ']') {
          at += 1;
          return array;
        }
        for (; ; ) {
          array.add(value());
          space();
          if (peek() == ',') {
            at += 1;
          } else {
            expect(']');
            return array;
          }
        }
      }
      if (c == '"') return string();
      for (String literal : new String[] {"true", "false", "null"}) {
        if (text.startsWith(literal, at)) {
          at += literal.length();
          return literal.equals("null") ? null : Boolean.valueOf(literal);
        }
      }
      int start = at;
      while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at += 1;
      if (start == at) throw new IOException("Not JSON at " + at);
      return Double.parseDouble(text.substring(start, at));
    }

    private String string() throws IOException {
      expect('"');
      StringBuilder out = new StringBuilder();
      for (; ; ) {
        if (at >= text.length()) throw new IOException("Not JSON: unterminated string");
        char c = text.charAt(at++);
        if (c == '"') return out.toString();
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char escape = text.charAt(at++);
        switch (escape) {
          case 'n' -> out.append('\n');
          case 't' -> out.append('\t');
          case 'r' -> out.append('\r');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
            at += 4;
          }
          default -> out.append(escape);
        }
      }
    }

    private void space() {
      while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) at += 1;
    }

    private char peek() {
      return at < text.length() ? text.charAt(at) : '\0';
    }

    private void expect(char c) throws IOException {
      if (peek() != c) throw new IOException("Not JSON at " + at + ": expected " + c);
      at += 1;
    }
  }
}
