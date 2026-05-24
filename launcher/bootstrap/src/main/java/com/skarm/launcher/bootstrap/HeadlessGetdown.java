package com.skarm.launcher.bootstrap;

import com.threerings.getdown.data.EnvConfig;
import com.threerings.getdown.launcher.Getdown;
import com.threerings.getdown.launcher.RotatingBackgrounds;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headless subclass of getdown's Getdown launcher.
 *
 * Getdown tries to fork a child JVM which is... not what we want,
 * and this keeps things nice and consistent thread wise.
 */
public final class HeadlessGetdown extends Getdown {

    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean launchRequested = false;
    private volatile Integer exitCode = null;

    public HeadlessGetdown(EnvConfig envc) {
        super(envc);
    }

    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public boolean wasLaunchRequested() { return launchRequested; }

    public Integer exitCode() { return exitCode; }

    @Override
    protected void launch() {
        // getdown's real launch() releases the update lock before running the app
        // (Getdown.java:776, the invokeDirect branch). We launch SK ourselves in
        // SkBootstrap, but we MUST still release the lock here — otherwise our
        // bootstrap getdown holds gettingdown.lock for the JVM's whole life, and
        // SK's own in-game getdown (bundled in projectx, same JVM) can't lock and
        // dies with MultipleGetdownRunning (OverlappingFileLockException).
        try {
            _app.releaseLock();
        } catch (Throwable t) {
            System.err.println("[HeadlessGetdown] releaseLock failed:");
            t.printStackTrace();
        }
        launchRequested = true;
        done.countDown();
    }

    @Override
    protected void exit(int code) {
        exitCode = code;
        done.countDown();
        // Don't throw, caller needs to see getdown's exit code
    }

    @Override
    protected Container createContainer() {
        return new Container();
    }

    @Override
    protected void configureContainer() { /* no-op */ }

    @Override
    protected void showContainer() { /* no-op */ }

    @Override
    protected void disposeContainer() { /* no-op */ }

    @Override
    protected void showDocument(String url) {
        System.out.println("[HeadlessGetdown] showDocument suppressed: " + url);
    }

    /**
     * Our own copy of getdown's message bundle. getdown's inherited _msgs is loaded
     * lazily during its UI init, so early-phase keys (e.g. "m.detecting_proxy",
     * fired during proxy detection before _msgs exists) would otherwise forward
     * untranslated. Loading it ourselves removes that timing dependency — it's the
     * same bundle, resolved by our classloader (getdown-pro.jar is on our classpath).
     */
    private static final java.util.ResourceBundle MSGS = loadMsgs();

    private static java.util.ResourceBundle loadMsgs() {
        try {
            return java.util.ResourceBundle.getBundle("com.threerings.getdown.messages");
        } catch (Throwable t) {
            System.err.println("[HeadlessGetdown] could not load message bundle: " + t);
            return null;
        }
    }

    /** Last translated status text, so progress-only ticks (null message) keep context. */
    private volatile String lastText;

    /** Last text actually forwarded, to coalesce getdown's many same-value ticks. */
    private volatile String lastForwarded;

    /**
     * getdown's async status entry point. In silent mode (-Dsilent=launch) the
     * superclass DROPS these before they reach updateStatus, so we tap them here —
     * above the gate — to feed the Android boot overlay regardless of silent. This
     * path also carries percent, which updateStatus lacks. We don't call super: the
     * Swing UI it would drive is stubbed out anyway.
     */
    @Override
    protected void setStatusAsync(String message, int percent, long remaining, boolean createUI) {
        forwardStatus(message, percent);
    }

    /**
     * getdown's StatusDisplay hook — direct status sink, used by a few paths that
     * bypass setStatusAsync (e.g. proxy detection). No percent here.
     */
    @Override
    public void updateStatus(String message) {
        forwardStatus(message, -1);
    }

    /**
     * Translate a getdown message KEY ("m.validating", …) to human text via our own
     * copy of the bundle, append percent if present, and forward to the Android boot
     * overlay via native. A null/empty message reuses the last text (progress-only
     * tick). Best-effort: never let overlay plumbing derail the actual update.
     */
    private void forwardStatus(String message, int percent) {
        String text;
        if (message != null && !message.isEmpty()) {
            try {
                text = (MSGS != null) ? MSGS.getString(message) : message;
            } catch (java.util.MissingResourceException e) {
                text = message; // already plain text, not a key
            }
            lastText = text;
        } else {
            text = lastText; // progress-only tick; keep the current phase text
        }
        if (text == null) return; // nothing meaningful to show yet
        if (percent >= 0) text = text + "  " + percent + "%";

        // Coalesce: getdown fires the same value many times per second (e.g. tens of
        // thousands of "Validating 90%"). Forwarding only on change spares the
        // cross-VM JNI round-trip and the overlay's redundant UI updates.
        if (text.equals(lastForwarded)) return;
        lastForwarded = text;

        // Visible in logcat (sk-stdout) so the full status sequence can be inspected.
        System.out.println("[HeadlessGetdown] status: key=" + message
            + " pct=" + percent + " -> " + text);
        try {
            SkBootstrap.nativeLaunchStatus(text);
        } catch (Throwable t) {
            // boot overlay is best-effort; never let it break getdown
        }
    }

    @Override
    public BufferedImage loadImage(String path) {
        return null;
    }

    @Override
    protected RotatingBackgrounds getBackground() {
        return null;
    }
}
