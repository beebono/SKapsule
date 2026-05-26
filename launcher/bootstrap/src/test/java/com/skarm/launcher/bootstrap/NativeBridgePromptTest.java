package com.skarm.launcher.bootstrap;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.lang.reflect.Method;

public class NativeBridgePromptTest {

    @Test
    public void testInvokeWithNullMethod() throws Exception {
        Method invokeMethod = NativeBridgePrompt.class.getDeclaredMethod("invoke", Method.class, Object[].class);
        invokeMethod.setAccessible(true);

        String result = (String) invokeMethod.invoke(null, null, new Object[0]);
        assertEquals("", result);
    }

    @Test
    public void testInvokeWithMethodThrowingException() throws Exception {
        Method invokeMethod = NativeBridgePrompt.class.getDeclaredMethod("invoke", Method.class, Object[].class);
        invokeMethod.setAccessible(true);

        Method throwingMethod = NativeBridgePromptTest.class.getDeclaredMethod("throwingMethod");

        String result = (String) invokeMethod.invoke(null, throwingMethod, new Object[0]);
        assertEquals("", result);
    }

    @Test
    public void testInvokeWithValidMethod() throws Exception {
        Method invokeMethod = NativeBridgePrompt.class.getDeclaredMethod("invoke", Method.class, Object[].class);
        invokeMethod.setAccessible(true);

        Method validMethod = NativeBridgePromptTest.class.getDeclaredMethod("validMethod");

        String result = (String) invokeMethod.invoke(null, validMethod, new Object[0]);
        assertEquals("success", result);
    }

    @Test
    public void testInvokeWithNullReturn() throws Exception {
        Method invokeMethod = NativeBridgePrompt.class.getDeclaredMethod("invoke", Method.class, Object[].class);
        invokeMethod.setAccessible(true);

        Method nullReturnMethod = NativeBridgePromptTest.class.getDeclaredMethod("nullReturnMethod");

        String result = (String) invokeMethod.invoke(null, nullReturnMethod, new Object[0]);
        assertEquals("", result);
    }

    public static String throwingMethod() {
        throw new RuntimeException("Test exception");
    }

    public static String validMethod() {
        return "success";
    }

    public static String nullReturnMethod() {
        return null;
    }
}
