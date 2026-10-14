package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code file} driver (secrets contract, section 4): {@code directory/<client>/<name>}, read
 * only after the real path is checked to lie under the directory and to end in exactly the client
 * and the name, so neither a link nor a case-insensitive file system can lead to another file.
 */
final class FileSecretStore implements SecretStore {

  private static final Logger LOG = LoggerFactory.getLogger(FileSecretStore.class);
  private static final Pattern CLIENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");

  private final String instance;
  private final Path root;
  private final int maxBytes;
  private final Set<Path> warnedReadable = ConcurrentHashMap.newKeySet();

  /**
   * @param root the directory, already resolved to its real path
   */
  FileSecretStore(String instance, Path root, int maxBytes) {
    this.instance = instance;
    this.root = root;
    this.maxBytes = maxBytes;
  }

  @Override
  public SecretResult find(ClientId client, String name) {
    Names.check(name);
    String c = client.value();
    if (!CLIENT.matcher(c).matches()) {
      return new SecretResult.Refused(
          "secret store '" + instance + "' (file) cannot hold a client id starting with a dot");
    }
    Path real;
    try {
      real = root.resolve(c).resolve(name).toRealPath();
    } catch (NoSuchFileException e) {
      return new SecretResult.NotFound();
    } catch (IOException e) {
      throw failure("cannot resolve", c, name, e);
    }
    Path parent = real.getParent();
    if (!real.startsWith(root)
        || parent == null
        || parent.getFileName() == null
        || !parent.getFileName().toString().equals(c)
        || real.getFileName() == null
        || !real.getFileName().toString().equals(name)) {
      LOG.warn(
          "secret store '{}': secret {} of client {} resolves outside the directory or to another"
              + " name; ignored",
          instance,
          name,
          c);
      return new SecretResult.NotFound();
    }
    try {
      BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
      if (!attributes.isRegularFile()) {
        return new SecretResult.NotFound();
      }
      warnIfReadableByOthers(real, c, name);
      byte[] bytes;
      try (InputStream in = Files.newInputStream(real)) {
        bytes = in.readNBytes(maxBytes + 1);
      }
      if (bytes.length > maxBytes) {
        throw new SecretStoreException(
            describe("is larger than maxBytes (" + maxBytes + ")", c, name), null);
      }
      String text = decode(bytes, c, name);
      if (text.endsWith("\r\n")) {
        text = text.substring(0, text.length() - 2);
      } else if (text.endsWith("\n")) {
        text = text.substring(0, text.length() - 1);
      }
      return text.isEmpty() ? new SecretResult.NotFound() : new SecretResult.Found(Secret.of(text));
    } catch (NoSuchFileException e) {
      return new SecretResult.NotFound();
    } catch (IOException e) {
      throw failure("cannot read", c, name, e);
    }
  }

  private String decode(byte[] bytes, String client, String name) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      // The cause is not attached: its message could describe the content.
      throw new SecretStoreException(describe("is not valid UTF-8", client, name), null);
    }
  }

  private void warnIfReadableByOthers(Path real, String client, String name) {
    PosixFileAttributeView view = Files.getFileAttributeView(real, PosixFileAttributeView.class);
    if (view == null) {
      return;
    }
    try {
      if (view.readAttributes().permissions().contains(PosixFilePermission.OTHERS_READ)
          && warnedReadable.add(real)) {
        LOG.warn(
            "secret store '{}': the file of secret {} of client {} is readable by every local user",
            instance,
            name,
            client);
      }
    } catch (IOException e) {
      LOG.debug("secret store '{}': could not read the permissions of a secret file", instance);
    }
  }

  private SecretStoreException failure(String what, String client, String name, IOException e) {
    // The I/O exception names the path (client and name only, both validated), never content.
    return new SecretStoreException(
        describe(what + " (" + e.getClass().getSimpleName() + ")", client, name), e);
  }

  private String describe(String what, String client, String name) {
    return "secret store '" + instance + "' (file): secret " + name + " of client " + client + " " + what;
  }

  @Override
  public String toString() {
    return "FileSecretStore[" + instance + "]";
  }
}
