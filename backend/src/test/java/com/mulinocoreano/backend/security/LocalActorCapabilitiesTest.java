package com.mulinocoreano.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalActorCapabilitiesTest {
    @Test
    void managerAndAdminCanDecideProcurement() {
        assertThat(LocalActorCapabilities.forRole("MANAGER"))
                .containsExactlyInAnyOrder("erp:read", "work:write", "procurement:decide");
        assertThat(LocalActorCapabilities.forRole("ADMIN"))
                .containsExactlyInAnyOrder("erp:read", "work:write", "procurement:decide");
    }

    @Test
    void qcAndOperatorCanWriteButNotDecide() {
        assertThat(LocalActorCapabilities.forRole("QC"))
                .containsExactlyInAnyOrder("erp:read", "work:write");
        assertThat(LocalActorCapabilities.forRole("OPERATOR"))
                .containsExactlyInAnyOrder("erp:read", "work:write");
    }

    @Test
    void viewerIsReadOnly() {
        assertThat(LocalActorCapabilities.forRole("VIEWER")).containsExactly("erp:read");
    }

    @Test
    void unknownRoleIsRejected() {
        assertThatThrownBy(() -> LocalActorCapabilities.forRole("HACKER"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
