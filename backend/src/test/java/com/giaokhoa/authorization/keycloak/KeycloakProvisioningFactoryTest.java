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
        // Chỉ khoá loại exception: thông điệp chứa chính đầu vào nên khoá câu chữ là khoá prose.
        assertThrows(IllegalStateException.class, () -> KeycloakProvisioningFactory.realmOf(null));
    }

    @Test
    void realmOfRejectsIssuerWithoutRealm() {
        assertThrows(
                IllegalStateException.class,
                () -> KeycloakProvisioningFactory.realmOf("http://127.0.0.1:18081/"));
    }
}
