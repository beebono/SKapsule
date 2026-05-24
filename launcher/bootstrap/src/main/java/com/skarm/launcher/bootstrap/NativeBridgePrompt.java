package com.skarm.launcher.bootstrap;

import co.frenchpress.CredentialPrompt;
import co.frenchpress.Credentials;

import java.lang.reflect.Method;

/**
 * Android {@link CredentialPrompt} that collects Steam Guard codes through a
 * native bridge to {@code GameActivity}.
 *
 * <p>Selected via {@code -Dfrenchpress.credentialPrompt=...} from sklauncher.c.
 * Username/password still come from the {@code FRENCHPRESS_STEAM_USER/PASS} env
 * (populated by the Android login screen), so {@link #promptForLogin} mirrors
 * {@code co.frenchpress.HeadlessEnvPrompt}; only the 2FA callbacks go interactive.
 *
 * <p><b>Classloader note.</b> This class and frenchpress are loaded by SK's
 * URLClassLoader (parent = the platform loader), but the native methods on
 * {@link SkBootstrap} were registered by sklauncher.c against the copy of
 * SkBootstrap on the JVM <i>system</i> classpath. Those are two distinct Class
 * objects, so we can't call {@code SkBootstrap.nativePrompt*} directly — the
 * symbolic reference would resolve to our (unregistered) copy and throw
 * {@link UnsatisfiedLinkError}. Instead we reflect onto the system-loader copy,
 * whose natives are live. The exchanged values are {@link String}s, defined by
 * the boot loader, so they cross the loader boundary cleanly.
 *
 * <p>The native calls block until the user submits a code; that is correct
 * because JavaSteam only invokes these when no Steam-Mobile-App push approval is
 * available (see the decompiled poll loop) — a code is then mandatory.
 */
public final class NativeBridgePrompt implements CredentialPrompt {

  private final Method nativePromptDeviceCode; // (boolean) -> String, may be null if unavailable
  private final Method nativePromptEmailCode;  // (String, boolean) -> String

  public NativeBridgePrompt () {
    Method device = null, email = null;
    try {
      // The registered SkBootstrap lives on the system classpath, not in SK's loader.
      Class<?> sysBoot = Class.forName("com.skarm.launcher.bootstrap.SkBootstrap",
          false, ClassLoader.getSystemClassLoader());
      device = sysBoot.getDeclaredMethod("nativePromptDeviceCode", boolean.class);
      email  = sysBoot.getDeclaredMethod("nativePromptEmailCode", String.class, boolean.class);
      device.setAccessible(true);
      email.setAccessible(true);
    } catch (Throwable t) {
      System.err.println("[frenchpress] NativeBridgePrompt: native bridge unavailable ("
          + t + "); 2FA codes can't be entered, push approval only");
    }
    this.nativePromptDeviceCode = device;
    this.nativePromptEmailCode = email;
  }

  @Override public Credentials promptForLogin () {
    String user = System.getenv("FRENCHPRESS_STEAM_USER");
    String pass = System.getenv("FRENCHPRESS_STEAM_PASS");
    if (user == null || user.isEmpty()) {
      return new Credentials("", ""); // empty username -> web account
    }
    return new Credentials(user, pass == null ? "" : pass);
  }

  @Override public String promptForDeviceCode (boolean prevWrong) {
    return invoke(nativePromptDeviceCode, prevWrong);
  }

  @Override public String promptForEmailCode (String email, boolean prevWrong) {
    return invoke(nativePromptEmailCode, email, prevWrong);
  }

  /** Reflectively call a SkBootstrap native; returns "" on any failure (login then fails fast). */
  private static String invoke (Method m, Object... args) {
    if (m == null) return "";
    try {
      Object r = m.invoke(null, args);
      return r != null ? (String) r : "";
    } catch (Throwable t) {
      System.err.println("[frenchpress] NativeBridgePrompt native call failed: " + t);
      return "";
    }
  }
}
