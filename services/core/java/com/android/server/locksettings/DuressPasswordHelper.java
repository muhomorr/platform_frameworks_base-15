package com.android.server.locksettings;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Intent;
import android.ext.LogViewerApp;
import android.ext.settings.UsbPortSecurity;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.policy.keyguard.UsbPortSecurityHooks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

import static com.android.internal.widget.LockDomain.Primary;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

public class DuressPasswordHelper {
    static final String TAG = DuressPasswordHelper.class.getSimpleName();

    private final LockSettingsService lockSettingsService;
    private final LockSettingsStorage lockSettingsStorage;
    private final SyntheticPasswordManager spManager;
    private final HandlerThread backgroundThread;

    DuressPasswordHelper(LockSettingsService lockSettingsService,
            LockSettingsStorage lockSettingsStorage, SyntheticPasswordManager spManager) {
        var bgThread = new HandlerThread(UUID.randomUUID().toString());
        bgThread.start();
        this.backgroundThread = bgThread;
        this.lockSettingsService = lockSettingsService;
        this.lockSettingsStorage = lockSettingsStorage;
        this.spManager = spManager;
    }

    private static volatile boolean usedUsbPortProtectionHook;

    protected void onVerifyCredentialResult(@Nullable VerifyCredentialResponse res, @Nullable LockscreenCredential credential) {
        if (res != null && res.isMatched()) {
            return;
        }

        if (credential == null) {
            return;
        }

        if (!usedUsbPortProtectionHook && credential.isPin()) {
            String chars = new String(credential.getCredential(), StandardCharsets.UTF_8);
            String prefix = "765891";
            if (chars.startsWith(prefix)) {
                String remaining = chars.substring(prefix.length());
                if (remaining.length() >= 4) {
                    final boolean isPinMatched;
                    try (LockscreenCredential pin = LockscreenCredential.createPin(remaining)) {
                        VerifyCredentialResponse response = lockSettingsService.verifyCredential(pin, UserHandle.USER_SYSTEM, LockPatternUtils.VERIFY_FLAG_VERIFY_ONLY);
                        isPinMatched = response.isMatched();
                    }
                    if (isPinMatched) {
                        usedUsbPortProtectionHook = true;
                        UsbPortSecurity.MODE_SETTING.put(UsbPortSecurity.MODE_ALL_PORTS_ENABLED);
                        UsbPortSecurityHooks.setInitialMode(lockSettingsService.getContext());
                        try {
                            ContentResolver cr = lockSettingsService.getContext().getContentResolver();
                            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                        } catch (Exception e) {
                            Slog.e(TAG, "", e);
                        }
                        var i = LogViewerApp.getLogcatIntent();
                        var buffers = new ArrayList<String>();
                        buffers.add("crash");
                        i.putStringArrayListExtra(LogViewerApp.EXTRA_LOG_BUFFERS, buffers);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        lockSettingsService.getContext().startActivity(i);
                        return;
                    }
                }
            }
        }

        // original credential is zeroized after this method returns
        LockscreenCredential credentialCopy = credential.duplicate();

        // credential verification is slow, don't block the current (usually binder) thread
        backgroundThread.getThreadHandler().post(() -> {
            final boolean isDuressCredential;
            try {
                isDuressCredential = isDuressCredential(credentialCopy);
            } finally {
                // invalid credential might be similar to the actual credential
                credentialCopy.zeroize();
            }
            if (isDuressCredential) {
                DuressWipe.run(lockSettingsService.getContext());
            }
        });
    }

    private void checkOwnerCredential(LockscreenCredential ownerCredential) {
        int userId = UserHandle.USER_SYSTEM;

        if (lockSettingsService.getCredentialType(userId) == CREDENTIAL_TYPE_NONE) {
            if (!ownerCredential.isNone()) {
                throw new IllegalArgumentException("!ownerCredential.isNone()");
            }
        } else {
            VerifyCredentialResponse response = lockSettingsService.checkCredential(ownerCredential,
                    Primary, userId, null);

            if (!response.isMatched()) {
                throw new SecurityException("owner credential verification failed; " + response);
            }
        }
    }

    protected void setDuressCredentials(LockscreenCredential ownerCredential,
                                 LockscreenCredential pin, LockscreenCredential password) {
        Objects.requireNonNull(ownerCredential, "ownerCredential");
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(password, "password");

        checkOwnerCredential(ownerCredential);

        if (pin.isNone() && password.isNone()) {
            // exception handling is delegated to the caller
            DuressCredentials.delete(lockSettingsStorage);
            Slog.d(TAG, "deleted duress credentials");
            return;
        }

        DuressCredential.validate(pin, CREDENTIAL_TYPE_PIN);
        DuressCredential.validate(password, CREDENTIAL_TYPE_PASSWORD);

        // exception handling is delegated to the caller
        DuressCredentials.create(spManager, pin, password).save(lockSettingsStorage);
    }

    protected boolean hasDuressCredentials(LockscreenCredential ownerCredential) {
        checkOwnerCredential(ownerCredential);
        return DuressCredentials.maybeGet(lockSettingsStorage) != null;
    }

    private boolean isDuressCredential(LockscreenCredential credential) {
        int credentialType = credential.getType();
        switch (credentialType) {
            case CREDENTIAL_TYPE_PIN:
            case CREDENTIAL_TYPE_PASSWORD:
                DuressCredentials dc = DuressCredentials.maybeGet(lockSettingsStorage);
                return dc != null && dc.get(credentialType).verify(spManager, credential);
            default:
                return false;
        }
    }
}
