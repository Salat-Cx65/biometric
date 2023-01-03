package com.tencent.soter.core.sotercore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;

import com.tencent.soter.core.model.ConstantsSoter;
import com.tencent.soter.core.model.ISoterExParameters;
import com.tencent.soter.core.model.SLogger;
import com.tencent.soter.core.model.SoterCoreResult;
import com.tencent.soter.core.model.SoterDelegate;
import com.tencent.soter.core.model.SoterErrCode;
import com.tencent.soter.core.model.SoterExParametersTrebleImpl;
import com.tencent.soter.core.model.SoterPubKeyModel;
import com.tencent.soter.soterserver.ISoterService;
import com.tencent.soter.soterserver.SoterExportResult;
import com.tencent.soter.soterserver.SoterExtraParam;
import com.tencent.soter.soterserver.SoterSessionResult;
import com.tencent.soter.soterserver.SoterSignResult;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

/**
 * The SOTER Core APIs Treble project
 */
public class SoterCoreTreble extends SoterCoreBase implements ConstantsSoter, SoterErrCode {

    public static final String TAG = "Soter.SoterCoreTreble";
    protected static final int DEFAULT_BLOCK_TIME = 3 * 1000; // Default synchronize block time
    private static final int DISCONNECT = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;
    private static final int DELAY_THRESHOLD = 30;
    //Fib(3)=2
    private static final int INITIAL_FIB_VALUE = 3;
    private static final Object lock = new Object();
    public static int uid;
    protected static ISoterService mSoterService;
    private static int connectState = DISCONNECT;
    private static boolean isInitializing;
    private static boolean isInitializeSuccessed;
    private static final SyncJob syncJob = new SyncJob();
    private Context mContext;
    private boolean canRetry = true;
    private int disconnectCount;
    private int noResponseCount = INITIAL_FIB_VALUE;
    private long lastBindTime;
    private boolean hasBind;
    private final Handler mMainLooperHandler = new Handler(Looper.getMainLooper());
    private SoterCoreTrebleServiceListener serviceListener;

    public static boolean isInitializing() {
        return isInitializing;
    }

    private static long getFib(long n) {
        if (n < 0) {
            return -1;
        } else if (n == 0) {
            return 0;
        } else if (n == 1 || n == 2) {
            return 1;
        } else {
            long c = 0, a = 1, b = 1;
            for (int i = 3; i <= n; i++) {
                c = a + b;
                a = b;
                b = c;
            }
            return c;
        }
    }    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {

        @Override
        public void binderDied() {
            // TODO Auto-generated method stub
            SLogger.i(TAG, "soter: binder died");
            if (mSoterService == null || mSoterService.asBinder() == null) {
                return;
            }

            mSoterService.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mSoterService = null;
            if (serviceListener != null) {
                serviceListener.onServiceBinderDied();
            }

            synchronized (lock) {
                connectState = DISCONNECT;
                unbindService();
                rebindService();
            }
        }
    };

    public void unbindService() {
        if (hasBind) {
            try {
                mContext.unbindService(mServiceConnection);
            } catch (Exception e) {
                SLogger.printErrStackTrace(TAG, e, "");
            } finally {
                hasBind = false;
            }
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(
                ComponentName className, IBinder service) {
            SLogger.i(TAG, "soter: onServiceConnected");
            synchronized (lock) {
                connectState = CONNECTED;
            }
            // when connected, reset fib and cancel retryFunc.
            noResponseCount = INITIAL_FIB_VALUE;
            mMainLooperHandler.removeCallbacks(retryFunc);
            try {
                service.linkToDeath(mDeathRecipient, 0);
                mSoterService = ISoterService.Stub.asInterface(service);
            } catch (RemoteException e) {
                SLogger.e(TAG, "soter: Binding deathRecipient is error - RemoteException" + e);
            }

            if (serviceListener != null) {
                serviceListener.onServiceConnected();
            }

            SLogger.i(TAG, "soter: Binding is done - Service connected");

            syncJob.countDown();
        }

        public void onServiceDisconnected(ComponentName className) {
            synchronized (lock) {
                SLogger.i(TAG, "soter: unBinding is done - Service disconnected");
                connectState = DISCONNECT;
                mSoterService = null;
                if (getFib(noResponseCount) > DELAY_THRESHOLD) {
                    //when delay time large than DELAY_THRESHOLD second, reset fib value
                    SLogger.i(TAG, "soter: rest fib, now is delay %dS", getFib(noResponseCount));
                    noResponseCount = INITIAL_FIB_VALUE;
                    mMainLooperHandler.removeCallbacks(retryFunc);
                }
                if (serviceListener != null) {
                    serviceListener.onServiceDisconnected();
                }

                rebindService();

                syncJob.countDown();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            SLogger.i(TAG, "soter: binding died");
            connectState = DISCONNECT;
            mSoterService = null;
            if (getFib(noResponseCount) > DELAY_THRESHOLD) {
                //when delay time large than DELAY_THRESHOLD second, reset fib value
                SLogger.i(TAG, "soter: rest fib, now is delay %dS", getFib(noResponseCount));
                noResponseCount = INITIAL_FIB_VALUE;
                mMainLooperHandler.removeCallbacks(retryFunc);
            }
            unbindService();
            rebindService();
        }
    };

    private void rebindService() {
        if (!canRetry) {
            return;
        }
        disconnectCount++;
        long duration = (SystemClock.elapsedRealtime() - lastBindTime) / 1000;
        long fib = getFib(disconnectCount);
        long delay = fib - duration;
        SLogger.i(TAG, "fib: %s, rebind delay: %sS", fib, delay);
        if (delay <= 0) {
            bindService();
        } else {
            mMainLooperHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    bindServiceIfNeeded();
                }
            }, delay * 1000);
        }
    }

    private void resetDisconnectCount() {
        disconnectCount = 0;
    }

    @Override
    public boolean initSoter(Context context) {
        mContext = context;
        SLogger.i(TAG, "soter: initSoter in");
        isInitializing = true;
        syncJob.doAsSyncJob(DEFAULT_BLOCK_TIME, new Runnable() {
            @Override
            public void run() {
                bindServiceIfNeeded();
                SLogger.i(TAG, "soter: initSoter binding");
            }
        });

        isInitializing = false;
        if (connectState == CONNECTED) {
            SLogger.i(TAG, "soter: initSoter finish");
            isInitializeSuccessed = true;
            return true;
        } else {
            connectState = DISCONNECT;
            SLogger.e(TAG, "soter: initSoter error");
            return false;
        }
    }

    @Override
    public boolean isTrebleServiceConnected() {
        return connectState == CONNECTED;
    }

    @Override
    public void triggerTrebleServiceConnecting() {
        resetDisconnectCount();
        bindServiceIfNeeded();
    }

    @Override
    public void releaseTrebleServiceConnection() {
        canRetry = false;
        unbindService();
    }

    @Override
    public void setTrebleServiceListener(SoterCoreTrebleServiceListener listener) {
        serviceListener = listener;
    }

    public void bindServiceIfNeeded() {
        try {
            if (connectState != CONNECTED || mSoterService == null || mSoterService.asBinder() == null || !mSoterService.asBinder().isBinderAlive() || !mSoterService.asBinder().pingBinder()) {
                SLogger.i(TAG, "soter: bindServiceIfNeeded try to bind");
                bindService();
            } else {
                SLogger.i(TAG, "no need rebind");
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: bindServiceIfNeeded fail: ");
        }
    }

    public void bindService() {
        bindService(false);
    }

    private void bindService(boolean isCycle) {
        Intent intent = new Intent();
        intent.setAction("com.tencent.soter.soterserver.ISoterService");
        intent.setPackage("com.tencent.soter.soterserver");

        if (mContext == null) {
            SLogger.e(TAG, "soter: bindService context is null ");
            return;
        }
        connectState = CONNECTING;

        if (serviceListener != null) {
            serviceListener.onStartServiceConnecting();
        }

        SLogger.i(TAG, "soter: bindService binding is start ");
        lastBindTime = SystemClock.elapsedRealtime();
        hasBind = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);

        scheduleTimeoutTask(isCycle);
    }

    private void scheduleTimeoutTask(boolean isCycle) {
        final long checkDelay = getFib(noResponseCount);
        SLogger.i(TAG, "soter: scheduleTimeoutTask isCycle:%b noResponseCount:%d checkDelay:%d ", isCycle, noResponseCount, checkDelay);
        if (isCycle || noResponseCount <= INITIAL_FIB_VALUE) {
            mMainLooperHandler.postDelayed(retryFunc, checkDelay * 1000);
        }
    }



    private final Runnable retryFunc = new Runnable() {
        @Override
        public void run() {
            if (!canRetry || !isInitializeSuccessed) {
                SLogger.i(TAG, "soter: retryFunc stop, canRetry:%b isInitializeSuccessed:%b", canRetry, isInitializeSuccessed);
                return;
            }

            noResponseCount++;
            if (connectState != CONNECTED) {
                SLogger.i(TAG, "soter: retryFunc bindservice no response: %d delay: %d", noResponseCount, getFib(noResponseCount));
                bindService(true);
            } else {
                SLogger.i(TAG, "soter: retryFunc stop, CONNECTED");
            }
        }
    };

    public boolean isNativeSupportSoter() {

        if (SoterDelegate.isTriggeredOOM()) {
            SLogger.w(TAG, "soter: the device has already triggered OOM. mark as not support");
            return false;
        }

        return true;
    }

    private boolean checkIfServiceNull() {
        if (mSoterService == null) {
            SLogger.w(TAG, "soter: soter service not found");
            if (serviceListener != null) {
                serviceListener.onNoServiceWhenCalling();
            }
            return true;
        }
        return false;
    }

    @Override
    public SoterCoreResult generateAppGlobalSecureKey() {
        SLogger.i(TAG, "soter: generateAppSecureKey in");

        if (!isNativeSupportSoter()) {
            return new SoterCoreResult(SoterErrCode.ERR_ASK_GEN_FAILED);
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(SoterErrCode.ERR_ASK_GEN_FAILED);
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            return new SoterCoreResult(SoterErrCode.ERR_ASK_GEN_FAILED);
        }

        try {
            if (mSoterService.generateAppSecureKey(uid) == SoterErrCode.ERR_OK) {
                return new SoterCoreResult(SoterErrCode.ERR_OK);
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: generateAppSecureKey fail: ");
        }
        return new SoterCoreResult(SoterErrCode.ERR_ASK_GEN_FAILED);
    }

    @Override
    public SoterCoreResult removeAppGlobalSecureKey() {
        SLogger.i(TAG, "soter: removeAppGlobalSecureKey in");

        if (!isNativeSupportSoter()) {
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_ASK);
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_ASK);
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_ASK);
        }

        try {
            if (mSoterService.removeAllAuthKey(uid) == SoterErrCode.ERR_OK) {
                return new SoterCoreResult(SoterErrCode.ERR_OK);
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: removeAppGlobalSecureKey fail: ");
        }
        return new SoterCoreResult(SoterErrCode.ERR_REMOVE_ASK);
    }

    @Override
    public boolean hasAppGlobalSecureKey() {
        SLogger.i(TAG, "soter: hasAppGlobalSecureKey in");

        if (!isNativeSupportSoter()) {
            return false;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return false;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return false;
        }

        try {
            return mSoterService.hasAskAlready(uid);
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: hasAppGlobalSecureKey fail: ");
            return false;
        }
    }

    @Override
    public boolean isAppGlobalSecureKeyValid() {
        SLogger.i(TAG, "soter: isAppGlobalSecureKeyValid in");
        return hasAppGlobalSecureKey() && getAppGlobalSecureKeyModel() != null;
    }

    @Override
    public SoterPubKeyModel getAppGlobalSecureKeyModel() {
        SLogger.i(TAG, "soter: getAppGlobalSecureKeyModel in");

        if (!isNativeSupportSoter()) {
            return null;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterExportResult soterExportResult;

        try {
            soterExportResult = mSoterService.getAppSecureKey(uid);
            byte[] rawBytes = soterExportResult.exportData;

            if (rawBytes != null && rawBytes.length > 0) {
                return SoterCoreBase.retrieveJsonFromExportedData(rawBytes);
            } else {
                SLogger.e(TAG, "soter: soter: key can not be retrieved");
                return null;
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getAppGlobalSecureKeyModel fail: ");
        }
        return null;
    }

    @Override
    public SoterCoreResult generateAuthKey(String authKeyName) {
        SLogger.i(TAG, "soter: generateAuthKey in");

        if (!isNativeSupportSoter()) {
            return new SoterCoreResult(SoterErrCode.ERR_AUTH_KEY_GEN_FAILED);
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(SoterErrCode.ERR_AUTH_KEY_GEN_FAILED);
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(SoterErrCode.ERR_AUTH_KEY_GEN_FAILED);
        }

        try {
            if (mSoterService.generateAuthKey(uid, authKeyName) == SoterErrCode.ERR_OK) {
                return new SoterCoreResult(SoterErrCode.ERR_OK);
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: generateAuthKey fail: ");
        }

        return new SoterCoreResult(SoterErrCode.ERR_AUTH_KEY_GEN_FAILED);
    }

    @Override
    public SoterCoreResult removeAuthKey(String authKeyName, boolean isAutoDeleteASK) {
        SLogger.i(TAG, "soter: removeAuthKey in");

        if (!isNativeSupportSoter()) {
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_AUTH_KEY);
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_AUTH_KEY);
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return new SoterCoreResult(SoterErrCode.ERR_REMOVE_AUTH_KEY);
        }

        try {
            if (mSoterService.removeAuthKey(uid, authKeyName) == SoterErrCode.ERR_OK) {
                if (isAutoDeleteASK) {
                    if (mSoterService.removeAllAuthKey(uid) == SoterErrCode.ERR_OK) {
                        return new SoterCoreResult(SoterErrCode.ERR_OK);
                    } else {
                        return new SoterCoreResult(SoterErrCode.ERR_REMOVE_ASK);
                    }
                }
                return new SoterCoreResult(SoterErrCode.ERR_OK);
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: removeAuthKey fail: ");
        }
        return new SoterCoreResult(SoterErrCode.ERR_REMOVE_AUTH_KEY);
    }

    @Override
    public Signature initAuthKeySignature(String useKeyAlias) throws InvalidKeyException, NoSuchProviderException,
            NoSuchAlgorithmException, KeyStoreException, IOException,
            CertificateException, UnrecoverableEntryException {
        return null;
    }

    @Override
    public boolean isAuthKeyValid(String authKeyName, boolean autoDelIfNotValid) {
        SLogger.i(TAG, "soter: isAuthKeyValid in");
        //todo
        return hasAuthKey(authKeyName) && getAuthKeyModel(authKeyName) != null;
    }

    @Override
    public SoterPubKeyModel getAuthKeyModel(String authKeyName) {
        SLogger.i(TAG, "soter: getAuthKeyModel in");

        if (!isNativeSupportSoter()) {
            return null;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterExportResult soterExportResult;

        try {
            soterExportResult = mSoterService.getAuthKey(uid, authKeyName);
            byte[] rawBytes = soterExportResult.exportData;
            if (rawBytes != null && rawBytes.length > 0) {
                return SoterCoreBase.retrieveJsonFromExportedData(rawBytes);
            } else {
                SLogger.e(TAG, "soter: key can not be retrieved");
                return null;
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getAuthKeyModel fail: ");
        }
        return null;
    }

    @Override
    public Signature getAuthInitAndSign(String useKeyAlias) {
        return null;
    }

    @Override
    public boolean hasAuthKey(String authKeyName) {

        SLogger.i(TAG, "soter: hasAuthKey in");

        if (!isNativeSupportSoter()) {
            return false;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return false;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return false;
        }

        try {
            return mSoterService.hasAuthKey(uid, authKeyName);
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: hasAuthKey fail: ");
            return false;
        }
    }

    @Override
    public SoterSessionResult initSigh(String kname, String challenge) {

        SLogger.i(TAG, "soter: initSigh in");

        if (!isNativeSupportSoter()) {
            return null;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterSessionResult result;

        try {
            result = mSoterService.initSigh(uid, kname, challenge);
            return result;
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: initSigh fail: ");
        }
        return null;
    }

    @Override
    public byte[] finishSign(long signSession) throws Exception {

        SLogger.i(TAG, "soter: finishSign in");

        if (!isNativeSupportSoter()) {
            return null;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return null;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return null;
        }

        SoterSignResult soterSignResult;
        byte[] rawBytes = new byte[0];
        try {
            soterSignResult = mSoterService.finishSign(signSession);
            rawBytes = soterSignResult.exportData;
            if (soterSignResult.resultCode != SoterErrCode.ERR_OK) {
                throw new Exception("finishSign error");
            }
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: finishSign fail: ");
        }
        return rawBytes;
    }

    public int getVersion() {
        SLogger.i(TAG, "soter: getVersion in");

        if (!isNativeSupportSoter()) {
            return 0;
        }

        if (mContext == null) {
            SLogger.w(TAG, "soter: context is null");
            return 0;
        }

        bindServiceIfNeeded();

        if (checkIfServiceNull()) {
            SLogger.w(TAG, "soter: soter service not found");
            return 0;
        }

        try {
            return mSoterService.getVersion();
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getVersion fail: ");
        }
        return 0;
    }

    @Override
    public void updateExtraParam() {
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mSoterService == null) {
                            SLogger.w(TAG, "soter: mSoterService is null");
                            return;
                        }
                        SoterExtraParam typeResult = mSoterService.getExtraParam(ISoterExParameters.FINGERPRINT_TYPE);
                        if (typeResult != null && typeResult.result instanceof Integer) {
                            SoterExParametersTrebleImpl.setParam(ISoterExParameters.FINGERPRINT_TYPE, typeResult.result);
                        }
                        SoterExtraParam posResult = mSoterService.getExtraParam(ISoterExParameters.FINGERPRINT_HARDWARE_POSITION);
                        if (posResult != null && posResult.result instanceof Integer[]) {
                            SoterExParametersTrebleImpl.setParam(ISoterExParameters.FINGERPRINT_HARDWARE_POSITION, posResult.result);
                        }
                    } catch (Exception e) {
                        SLogger.printErrStackTrace(TAG, e, "soter: getExtraParam fail");
                    }
                }
            });
            thread.start();
        } catch (Exception e) {
            SLogger.printErrStackTrace(TAG, e, "soter: getExtraParam fail");
        }
    }






}