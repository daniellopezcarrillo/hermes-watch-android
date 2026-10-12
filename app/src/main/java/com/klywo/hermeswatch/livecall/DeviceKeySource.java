package com.klywo.hermeswatch.livecall;

import android.content.Context;
import com.klywo.hermeswatch.identity.AndroidDeviceIdentity;
import com.klywo.hermeswatch.identity.DeviceIdentityStore;

/**
 * Android adapter over the existing per-device hgidentity store. It only loads
 * an existing key and never creates or resets one; provisioning stays an explicit
 * separate user action. Run {@link #load()} off the UI thread.
 */
public final class DeviceKeySource implements LiveSignalingSession.KeySource {
    private final DeviceIdentityStore store;

    public DeviceKeySource(Context context) {
        this.store = AndroidDeviceIdentity.open(context);
    }

    @Override public byte[] load() throws Exception {
        return store.load();
    }
}
