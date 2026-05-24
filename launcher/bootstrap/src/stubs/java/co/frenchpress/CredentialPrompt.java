package co.frenchpress;

/**
 * Compile-only API stub of frenchpress's CredentialPrompt — just the abstract
 * methods NativeBridgePrompt implements. The real interface (Java 25) is on SK's
 * classloader at runtime; this stub exists only so the JDK 21 bootstrap compiler
 * has matching symbols. NOT packaged into sk-bootstrap.jar. Keep signatures in
 * sync with frenchpress/src/main/java/co/frenchpress/CredentialPrompt.java.
 */
public interface CredentialPrompt {
  Credentials promptForLogin ();
  String promptForDeviceCode (boolean prevWrong);
  String promptForEmailCode (String email, boolean prevWrong);
}
