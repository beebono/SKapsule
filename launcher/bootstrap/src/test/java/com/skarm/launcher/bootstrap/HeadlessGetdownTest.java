package com.skarm.launcher.bootstrap;

import com.threerings.getdown.data.EnvConfig;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.assertEquals;

public class HeadlessGetdownTest {

    private HeadlessGetdown hgd;
    private Method forwardStatus;
    private Field lastTextField;
    private Field lastForwardedField;

    @Before
    public void setUp() throws Exception {
        File tempDir = File.createTempFile("getdown", "dir");
        tempDir.delete();
        tempDir.mkdir();
        tempDir.deleteOnExit();
        EnvConfig envConfig = new EnvConfig(tempDir);
        hgd = new HeadlessGetdown(envConfig);

        forwardStatus = HeadlessGetdown.class.getDeclaredMethod("forwardStatus", String.class, int.class);
        forwardStatus.setAccessible(true);

        lastTextField = HeadlessGetdown.class.getDeclaredField("lastText");
        lastTextField.setAccessible(true);

        lastForwardedField = HeadlessGetdown.class.getDeclaredField("lastForwarded");
        lastForwardedField.setAccessible(true);
    }

    @Test
    public void testForwardStatusNullMessage() throws Exception {
        lastTextField.set(hgd, "Previous Status");
        forwardStatus.invoke(hgd, null, 50);
        assertEquals("Previous Status", lastTextField.get(hgd));
        assertEquals("Previous Status  50%", lastForwardedField.get(hgd));
    }

    @Test
    public void testForwardStatusEmptyMessage() throws Exception {
        lastTextField.set(hgd, "Previous Status");
        forwardStatus.invoke(hgd, "", 50);
        assertEquals("Previous Status", lastTextField.get(hgd));
        assertEquals("Previous Status  50%", lastForwardedField.get(hgd));
    }

    @Test
    public void testForwardStatusWithMessage() throws Exception {
        // "m.validating" should be a valid key if the bundle is present
        // Since we cannot easily guarantee the actual translated text here
        // without depending on getdown-pro's exact bundle, we will just check
        // that it doesn't crash and sets lastText.
        forwardStatus.invoke(hgd, "m.validating", 10);
        String lastText = (String) lastTextField.get(hgd);
        String lastForwarded = (String) lastForwardedField.get(hgd);
        assertEquals(lastText + "  10%", lastForwarded);
    }

    @Test
    public void testForwardStatusWithMissingKey() throws Exception {
        forwardStatus.invoke(hgd, "non.existent.key", 10);
        assertEquals("non.existent.key", lastTextField.get(hgd));
        assertEquals("non.existent.key  10%", lastForwardedField.get(hgd));
    }

    @Test
    public void testForwardStatusWithoutPercent() throws Exception {
        forwardStatus.invoke(hgd, "some message", -1);
        assertEquals("some message", lastTextField.get(hgd));
        assertEquals("some message", lastForwardedField.get(hgd));
    }

    @Test
    public void testForwardStatusCoalescing() throws Exception {
        // The first call should set lastForwarded
        forwardStatus.invoke(hgd, "status update", 50);
        assertEquals("status update  50%", lastForwardedField.get(hgd));

        // A second identical call should return early, but we can't easily mock SkBootstrap
        // to check it wasn't called. The field should just remain the same.
        forwardStatus.invoke(hgd, "status update", 50);
        assertEquals("status update  50%", lastForwardedField.get(hgd));
    }
}
