package com.aichef;

import com.aichef.controller.MiniAppTaskController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiChefApplicationTests {

    @Test
    void coreClassesExist() {
        assertNotNull(MiniAppTaskController.class);
    }
}
