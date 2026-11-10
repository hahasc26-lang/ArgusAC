package com.example.argusac.setback;

import com.example.argusac.setback.handler.CancelSetbackHandler;
import com.example.argusac.setback.handler.HardSetbackHandler;
import com.example.argusac.setback.handler.SetbackHandler;
import com.example.argusac.setback.handler.SoftSetbackHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetbackModeHandlersTest {

    @Test
    @DisplayName("验证三种回退处理器均实现 SetbackHandler 接口")
    void testHandlersImplementInterface() {
        assertTrue(SetbackHandler.class.isAssignableFrom(CancelSetbackHandler.class));
        assertTrue(SetbackHandler.class.isAssignableFrom(SoftSetbackHandler.class));
        assertTrue(SetbackHandler.class.isAssignableFrom(HardSetbackHandler.class));
    }

    @Test
    @DisplayName("验证各 Handler 在传入空参数时的防御性处理")
    void testNullArgumentsHandling() {
        CancelSetbackHandler cancelHandler = new CancelSetbackHandler(null, null, null, null);
        SoftSetbackHandler softHandler = new SoftSetbackHandler(null, null, null);
        HardSetbackHandler hardHandler = new HardSetbackHandler(null, null, null, null);

        assertFalse(cancelHandler.handle(null, null, 0, "test", null));
        assertFalse(softHandler.handle(null, null, 0, "test", null));
        assertFalse(hardHandler.handle(null, null, 0, "test", null));
    }
}

