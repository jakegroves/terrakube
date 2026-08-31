package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerraformLoginPropertiesTest {

    @Test
    void defaultsAreSafe() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        assertFalse(p.isEnabled());
        assertEquals(30, p.getDefaultDays());
        assertEquals(90, p.getMaxDays());
        assertEquals(300000, p.getCleanupIntervalMs());
    }

    @Test
    void maxDaysIsClampedToOneYear() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setMaxDays(100000);
        p.normalize();
        assertEquals(365, p.getMaxDays());
    }

    @Test
    void maxDaysBelowOneIsClampedUp() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setMaxDays(0);
        p.normalize();
        assertEquals(1, p.getMaxDays());
    }

    @Test
    void enabledWithoutApiUrlFailsFast() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setEnabled(true);
        p.setApiUrl("  ");
        assertThrows(IllegalStateException.class, p::normalize);
    }

    @Test
    void disabledWithoutApiUrlIsFine() {
        TerraformLoginProperties p = new TerraformLoginProperties();
        p.setEnabled(false);
        assertDoesNotThrow(p::normalize);
    }
}
