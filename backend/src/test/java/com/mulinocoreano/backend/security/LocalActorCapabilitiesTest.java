package com.mulinocoreano.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalActorCapabilitiesTest {
    @Test
    void onlyManagerCanDecideProcurement() {
        assertThat(LocalActorCapabilities.forRole("MANAGER"))
                .containsExactlyInAnyOrder("erp:read", "work:write", "procurement:decide");
    }

    @Test
    void operatorCanWriteButNotDecide() {
        assertThat(LocalActorCapabilities.forRole("OPERATOR"))
                .containsExactlyInAnyOrder("erp:read", "work:write");
    }

    @Test
    void adminAndQcAreReadOnlyLikeTheRemovedAuth0Rules() {
        assertThat(LocalActorCapabilities.forRole("ADMIN")).containsExactly("erp:read");
        assertThat(LocalActorCapabilities.forRole("QC")).containsExactly("erp:read");
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
