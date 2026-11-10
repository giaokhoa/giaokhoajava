package com.giaokhoa.authorization.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KeycloakProvisioningFactoryTest {

    @Test
    void realmOfReturnsLastRealmSegmentOfIssuer() {
        assertEquals(
                "giaokhoa-it",
                KeycloakProvisioningFactory.realmOf("http://127.0.0.1:18081/realms/giaokhoa-it"));
    }

    @Test
    void realmOfRejectsNullIssuer() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> KeycloakProvisioningFactory.realmOf(null));
        assertEquals("Keycloak issuer URI has no realm: null", failure.getMessage());
    }

    @Test
    void realmOfRejectsIssuerWithoutRealm() {
        assertThrows(
                IllegalStateException.class,
                () -> KeycloakProvisioningFactory.realmOf("http://127.0.0.1:18081/"));
    }
}
