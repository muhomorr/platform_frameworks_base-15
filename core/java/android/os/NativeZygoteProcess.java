/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.util.Pair;

import com.android.internal.os.Zygote;
import com.android.internal.os.ZygoteExtraArgs;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Maintains communication state with the native zygote processes. This class is responsible
 * for starting processes on behalf of the {@link android.os.Process} class.
 *
 * TODO(b/442732151): This class is still evolving and we plan to add more methods and achieve
 * closer parity with matching some of the provided arguments.
 *
 * @hide
 */
public class NativeZygoteProcess implements IZygoteProcess {
    private static final String LOG_TAG = "NativeZygoteProcess";

    private LocalSocketAddress mSocketAddress;
    private LocalSocket mSocket;

    public NativeZygoteProcess() {
        mSocketAddress = new LocalSocketAddress(Zygote.NATIVE_SOCKET_NAME,
                                                LocalSocketAddress.Namespace.RESERVED);
    }

    public NativeZygoteProcess(LocalSocketAddress socketAddress) {
        mSocketAddress = socketAddress;
    }

    private static native void nativePrewarmNativeZygote();

    /** Prewarm the native zygote daemon before actually using it. */
    public static void prewarmNativeZygote() {
        nativePrewarmNativeZygote();
    }

    private static native boolean nativeEnsureNativeZygoteReadyBlocking();

    private synchronized void connectToZygote() throws IOException {
        if (mSocket == null) {
            if (!nativeEnsureNativeZygoteReadyBlocking()) {
                throw new IOException("Timed out to start zygote_next");
            }
            mSocket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
            try {
                mSocket.connect(mSocketAddress);
            } catch (IOException e) {
                mSocket = null;
                throw e;
            }
        }
    }

    private static native int nativeStartNativeProcess(long selinuxFlags,
            FileDescriptor fd, int uid, int gid, long startSeq, String packageName, String niceName,
            int targetSdkVersion, boolean startChildZygote, int runtimeFlags, String seInfo,
            boolean isTopApp)
            throws IOException;

    private static native int nativeStartNativeChildZygote(long selinuxFlags,
            FileDescriptor parentFd, int uid, int gid, String niceName, String seInfo,
            int targetSdkVersion, int runtimeFlags, String serverAddress, int uidRangeStart,
            int uidRangeEnd, String allowedLibPath, String librarySearchPaths, boolean isShared,
            String zipPath, String nativeSharedLibPath, String libraryPath, String preloadFunc)
            throws IOException;

    private static long getSelinuxFlags(@Nullable String flatExtraArgs) {
        if (flatExtraArgs != null) {
            String value = flatExtraArgs.substring(flatExtraArgs.indexOf('=') + 1);
            var extraArgs = ZygoteExtraArgs.parse(value);
            return extraArgs.selinuxFlags;
        }
        return 0L;
    }

    @Override
    public final Process.ProcessStartResult start(@NonNull final String processClass,
                                                  final String niceName,
                                                  int uid, int gid, @Nullable int[] gids,
                                                  int runtimeFlags, int mountExternal,
                                                  int targetSdkVersion,
                                                  @Nullable String seInfo,
                                                  @NonNull String abi,
                                                  @Nullable String instructionSet,
                                                  @Nullable String appDataDir,
                                                  @Nullable String invokeWith,
                                                  @Nullable String packageName,
                                                  int zygotePolicyFlags,
                                                  boolean isTopApp,
                                                  @Nullable long[] disabledCompatChanges,
                                                  @Nullable long[] enabledCompatChanges,
                                                  boolean useDeliQueue,
                                                  @Nullable Map<String, Pair<String, Long>>
                                                          pkgDataInfoMap,
                                                  @Nullable Map<String, Pair<String, Long>>
                                                          allowlistedDataInfoList,
                                                  boolean bindMountAppsData,
                                                  boolean bindMountAppStorageDirs,
                                                  boolean bindOverrideSysprops,
                                                  long startSeq,
                                                  @Nullable String[] zygoteArgs,
                                                  @Nullable String flatExtraArgs) {
        // there are currently 3 custom runtime flags:
        // - DISABLE_HARDENED_MALLOC and ENABLE_COMPAT_VA_39_BIT require exec spawning which is not
        // currently supported for native zygote
        // - FORCIBLY_ENABLE_MEMORY_TAGGING is low-impact and isn't needed in practice since native
        // zygote is mainly used by Chromium browsers which opt-in to memory tagging
        runtimeFlags &= ~Zygote.CUSTOM_RUNTIME_FLAGS;

        int pid;
        try {
            connectToZygote();
            pid = nativeStartNativeProcess(getSelinuxFlags(flatExtraArgs), mSocket.getFileDescriptor(), uid, gid, startSeq,
                    packageName, niceName, targetSdkVersion, /*startChildZygote=*/false,
                    runtimeFlags, seInfo, isTopApp);
            if (pid == -1) {
                throw new RuntimeException("Failed to fork a native process");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start a native process", e);
        }
        Process.ProcessStartResult result = new Process.ProcessStartResult();
        result.pid = pid;
        result.usingWrapper = false;
        return result;
    }

    @Override
    public ChildZygoteProcess startChildZygote(final String processClass,
                                               final String niceName,
                                               int uid, int gid, int[] gids,
                                               int runtimeFlags,
                                               String seInfo,
                                               String abi,
                                               String acceptedAbiList,
                                               String instructionSet,
                                               int uidRangeStart,
                                               int uidRangeEnd,
                                               ApplicationInfo appInfo,
                                               @Nullable String flatExtraArgs) {
        // see comment in start() above
        runtimeFlags &= ~Zygote.CUSTOM_RUNTIME_FLAGS;

        // Create an unguessable address in the global abstract namespace.
        String serverAddress = processClass + "/" + UUID.randomUUID().toString();
        // The address of abstract socket should be prefixed with '@'.  LocalSocket.connect()
        // internally adds a leading null-byte, but we have to explicitly add '@' when passing the
        // path to Native Zygote over the JNI code.
        String serverAddressForNative = "@" + serverAddress;

        LoadedApk loadedApk = new LoadedApk(
                /*activityThread*/ null,
                appInfo,
                /*compatInfo*/ null,
                /*classLoader*/ null,
                /*securityViolation*/ false,
                /*includeCode*/ true,
                /*registerPackage*/ false);
        LoadedApk.LinkerNamespaceParams params = loadedApk.createLinkerNamespaceParams();

        int pid;
        try {
            connectToZygote();
            pid = nativeStartNativeChildZygote(getSelinuxFlags(flatExtraArgs), mSocket.getFileDescriptor(), uid, gid, niceName,
                    seInfo, appInfo.targetSdkVersion, runtimeFlags, serverAddressForNative,
                    uidRangeStart, uidRangeEnd, params.permittedLibsDir, params.libPath,
                    params.isShared, params.zipPath, params.nativeSharedLibs,
                    appInfo.zygotePreloadNativeLib, appInfo.zygotePreloadNativeFunc);
            if (pid == -1) {
                throw new RuntimeException("Failed to fork a Native Child Zygote process");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start a Native Child Zygote process", e);
        }
        return ChildZygoteProcess.createNativeChildZygoteProcess(
                new LocalSocketAddress(serverAddress), pid, uid);
    }

    @Override
    public LocalSocketAddress getPrimarySocketAddress() {
        return mSocketAddress;
    }

    @Override
    public boolean preloadApp(ApplicationInfo appInfo, String abi) {
        return false;
    }

    @Override
    public void close() {
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException ex) {
                Log.e(LOG_TAG, "I/O exception on routine close", ex);
            }
        }
    }
}
