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

    @Override
    public BufferedImage loadImage(String path) {
        return null;
    }

    @Override
    protected RotatingBackgrounds getBackground() {
        return null;
    }
}
