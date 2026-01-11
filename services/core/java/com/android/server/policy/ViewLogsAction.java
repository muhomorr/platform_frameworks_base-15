package com.android.server.policy;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.ext.LogViewerApp;
import android.os.UserHandle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Slog;
import android.view.WindowManager;
import android.widget.EditText;

import com.android.internal.R;
import com.android.internal.globalactions.SinglePressAction;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import java.util.ArrayList;

class ViewLogsAction extends SinglePressAction {
    private static final String TAG = "ViewLogsAction";

    private final Context context;

    ViewLogsAction(Context context) {
        super(com.android.internal.R.drawable.ic_info,
            R.string.view_logs);
        this.context = context;
    }

    @Override
    public boolean showDuringKeyguard() {
        return true;
    }

    @Override
    public boolean showBeforeProvisioning() {
        return true;
    }

    @Override
    public void onPress() {
        var lpu = new LockPatternUtils(context);
        int credType = lpu.getCredentialTypeForUser(UserHandle.USER_SYSTEM);
        if (credType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            launchLogViewer();
        } else if (credType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD || credType == LockPatternUtils.CREDENTIAL_TYPE_PIN) {
            var editor = new EditText(context);
            editor.setInputType(credType == LockPatternUtils.CREDENTIAL_TYPE_PIN ?
                    InputType.TYPE_CLASS_NUMBER : // TYPE_NUMBER_VARIATION_PASSWORD shows the full keyboard layout
                    InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editor.setTransformationMethod(PasswordTransformationMethod.getInstance());

            var b = new AlertDialog.Builder(context);
            int title = credType == LockPatternUtils.CREDENTIAL_TYPE_PIN ?
                    R.string.kg_pin_instructions :
                    R.string.kg_password_instructions;
            b.setTitle(title);
            b.setView(editor);
            b.setPositiveButton(R.string.kg_login_submit_button, (dialog, which) -> {
                String credentialText = editor.getText().toString();
                editor.setText("");

                var cred = credType == LockPatternUtils.CREDENTIAL_TYPE_PIN ?
                        LockscreenCredential.createPin(credentialText) :
                        LockscreenCredential.createPassword(credentialText);

                try (cred) {
                    if (lpu.checkCredential(cred, UserHandle.USER_SYSTEM, null)) {
                        launchLogViewer();
                    }
                } catch (LockPatternUtils.RequestThrottledException e) {
                    Slog.d(TAG, "", e);
                }
                context.getMainThreadHandler().postDelayed(() -> {
                    // clear credential from memory
                    System.gc();
                    System.runFinalization();
                    System.gc();
                }, 5000L);

            });
            Dialog d = b.create();
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            d.show();
            editor.requestFocus();
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void launchLogViewer() {
        var i = LogViewerApp.getLogcatIntent();
        var buffers = new ArrayList<String>();
        buffers.add("crash");
        i.putStringArrayListExtra(LogViewerApp.EXTRA_LOG_BUFFERS, buffers);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
