package com.klywo.hermeswatch.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ConnectionFlowTest {
    private ConnectionFlow.Step step(ConnectionFlow.Identity identity, boolean ready,
            boolean connected, boolean paired, boolean endpoint, boolean busy, boolean retiring) {
        return ConnectionFlow.step(identity, ready, connected, paired, endpoint, busy, retiring);
    }
    @Test public void absentIdentityOffersCreationNotConnection() {
        assertEquals(ConnectionFlow.Step.CREATE, step(ConnectionFlow.Identity.ABSENT, false, false, false, false, false, false));
    }
    @Test public void identityFailureNeverOffersCreation() {
        assertEquals(ConnectionFlow.Step.IDENTITY_ERROR, step(ConnectionFlow.Identity.ERROR, false, false, false, true, false, false));
    }
    @Test public void checkingDoesNotOfferCreation() {
        assertEquals(ConnectionFlow.Step.WORKING, step(ConnectionFlow.Identity.CHECKING, false, false, false, false, false, false));
    }
    @Test public void readyIdentityNeedsEndpoint() {
        assertEquals(ConnectionFlow.Step.ENDPOINT, step(ConnectionFlow.Identity.READY, true, false, false, false, false, false));
    }
    @Test public void savedEndpointShowsOrdinaryConnection() {
        assertEquals(ConnectionFlow.Step.CONNECT, step(ConnectionFlow.Identity.READY, true, false, false, true, false, false));
    }
    @Test public void retirementBlocksRetryAndCreation() {
        assertEquals(ConnectionFlow.Step.WORKING, step(ConnectionFlow.Identity.READY, true, false, false, true, false, true));
        assertEquals(ConnectionFlow.Step.WORKING, step(ConnectionFlow.Identity.ABSENT, false, false, false, false, false, true));
    }
    @Test public void pendingPairingWinsOverCollapsedSettings() {
        assertEquals(ConnectionFlow.Step.PAIRING, step(ConnectionFlow.Identity.READY, true, true, false, false, false, true));
    }
    @Test public void pairedConnectionDoesNotRequireFreeAdmissionGate() {
        assertEquals(ConnectionFlow.Step.PAIRED, step(ConnectionFlow.Identity.READY, true, true, true, true, false, true));
    }
    @Test public void busyStatePreventsDoubleCreation() {
        assertEquals(ConnectionFlow.Step.WORKING, step(ConnectionFlow.Identity.ABSENT, false, false, false, false, true, false));
    }
    @Test public void pausedIdentityCannotConnectWithoutControllerReadiness() {
        assertEquals(ConnectionFlow.Step.IDENTITY_ERROR, step(ConnectionFlow.Identity.READY, false, false, false, true, false, false));
    }
}
