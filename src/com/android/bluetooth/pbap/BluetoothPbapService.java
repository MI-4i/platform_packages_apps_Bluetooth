/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.IBluetoothPbap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;

import javax.obex.ServerSession;

public class BluetoothPbapService extends Service {

    private static final String TAG = "BluetoothPbapService";

    /**
     * To enable PBAP debug logging - run below cmd in adb shell, and restart
     * com.android.bluetooth process. only enable VERBOSE log:
     * "setprop log.tag.BluetoothPbapService VERBOSE"; enable both VERBOSE and DEBUG log:
     * "setprop log.tag.BluetoothPbapService DEBUG"
     */

    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE)
            || Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Intent indicating incoming connection request which is sent to
     * BluetoothPbapActivity
     */
    public static final String ACCESS_REQUEST_ACTION = "com.android.bluetooth.pbap.accessrequest";

    /**
     * Intent indicating incoming connection request accepted by user which is
     * sent from BluetoothPbapActivity
     */
    public static final String ACCESS_ALLOWED_ACTION = "com.android.bluetooth.pbap.accessallowed";

    /**
     * Intent indicating incoming connection request denied by user which is
     * sent from BluetoothPbapActivity
     */
    public static final String ACCESS_DISALLOWED_ACTION =
            "com.android.bluetooth.pbap.accessdisallowed";

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    public static final String AUTH_CHALL_ACTION = "com.android.bluetooth.pbap.authchall";

    /**
     * Intent indicating obex session key input complete by user which is sent
     * from BluetoothPbapActivity
     */
    public static final String AUTH_RESPONSE_ACTION = "com.android.bluetooth.pbap.authresponse";

    /**
     * Intent indicating user canceled obex authentication session key input
     * which is sent from BluetoothPbapActivity
     */
    public static final String AUTH_CANCELLED_ACTION = "com.android.bluetooth.pbap.authcancelled";

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothPbapActivity
     */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.pbap.userconfirmtimeout";

    /**
     * Intent Extra name indicating always allowed which is sent from
     * BluetoothPbapActivity
     */
    public static final String EXTRA_ALWAYS_ALLOWED = "com.android.bluetooth.pbap.alwaysallowed";

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothPbapActivity
     */
    public static final String EXTRA_SESSION_KEY = "com.android.bluetooth.pbap.sessionkey";

    public static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    public static final int MSG_SERVERSESSION_CLOSE = 5000;

    public static final int MSG_SESSION_ESTABLISHED = 5001;

    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_OBEX_AUTH_CHALL = 5003;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int AUTH_TIMEOUT = 3;

    private static final int PORT_NUM = 19;

    private static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    private static final int TIME_TO_WAIT_VALUE = 6000;

    // Ensure not conflict with Opp notification ID
    private static final int NOTIFICATION_ID_ACCESS = -1000001;

    private static final int NOTIFICATION_ID_AUTH = -1000002;

    private PowerManager.WakeLock mWakeLock = null;

    private BluetoothAdapter mAdapter;

    private SocketAcceptThread mAcceptThread = null;

    private BluetoothPbapAuthenticator mAuth = null;

    private BluetoothPbapObexServer mPbapServer;

    private static BluetoothPbapVcardManager sVcardManager;

    private ServerSession mServerSession = null;

    private BluetoothServerSocket mServerSocket = null;

    private BluetoothSocket mConnSocket = null;

    private BluetoothDevice mRemoteDevice = null;

    private static String sLocalPhoneNum = null;

    private static String sLocalPhoneName = null;

    private static String sRemoteDeviceName = null;

    private boolean mHasStarted = false;

    private volatile boolean mInterrupted;

    private int mState;

    private int mStartId = -1;

    public BluetoothPbapService() {
        mState = BluetoothPbap.STATE_DISCONNECTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (VERBOSE) Log.v(TAG, "Pbap Service onCreate");

        mInterrupted = false;
        mAdapter = (BluetoothAdapter)getSystemService(Context.BLUETOOTH_SERVICE);
        sVcardManager = new BluetoothPbapVcardManager(BluetoothPbapService.this);
        if (!mHasStarted) {
            mHasStarted = true;
            if (VERBOSE) Log.v(TAG, "Starting PBAP service");

            int state = mAdapter.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                        .obtainMessage(START_LISTENER), TIME_TO_WAIT_VALUE);
            }
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (VERBOSE) Log.v(TAG, "Pbap Service onStart");

        mStartId = startId;
        if (mAdapter == null) {
            Log.w(TAG, "Stopping BluetoothPbapService: "
                    + "device does not have BT or device is not ready");
            // Release all resources
            closeService();
        } else {
            parseIntent(intent);
        }
    }

    // process the intent from receiver
    private void parseIntent(final Intent intent) {
        String action = intent.getExtras().getString("action");
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        boolean removeTimeoutMsg = true;
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            removeTimeoutMsg = false;
            if (state == BluetoothAdapter.STATE_OFF) {
                // Release all resources
                closeService();
            }
        } else if (action.equals(ACCESS_ALLOWED_ACTION)) {
            if (intent.getBooleanExtra(EXTRA_ALWAYS_ALLOWED, false)) {
                boolean result = mRemoteDevice.setTrust(true);
                if (VERBOSE) Log.v(TAG, "setTrust() result=" + result);
            }
            try {
                if (mConnSocket != null) {
                    startObexServerSession();
                } else {
                    stopObexServerSession();
                }
            } catch (IOException ex) {
                Log.e(TAG, "Caught the error: " + ex.toString());
            }
        } else if (action.equals(ACCESS_DISALLOWED_ACTION)) {
            stopObexServerSession();
        } else if (action.equals(AUTH_RESPONSE_ACTION)) {
            String sessionkey = intent.getStringExtra(EXTRA_SESSION_KEY);
            notifyAuthKeyInput(sessionkey);
        } else if (action.equals(AUTH_CANCELLED_ACTION)) {
            notifyAuthCancelled();
        } else {
            removeTimeoutMsg = false;
        }

        if (removeTimeoutMsg) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
        }
    }

    @Override
    public void onDestroy() {
        if (VERBOSE) Log.v(TAG, "Pbap Service onDestroy");

        super.onDestroy();
        setState(BluetoothPbap.STATE_DISCONNECTED, BluetoothPbap.RESULT_CANCELED);
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (VERBOSE) Log.v(TAG, "Pbap Service onBind");
        return mBinder;
    }

    private void startRfcommSocketListener() {
        if (VERBOSE) Log.v(TAG, "Pbap Service startRfcommSocketListener");

        if (mServerSocket == null) {
            if (!initSocket()) {
                closeService();
                return;
            }
        }
        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("BluetoothPbapAcceptThread");
            mAcceptThread.start();
        }
    }

    private final boolean initSocket() {
        if (VERBOSE) Log.v(TAG, "Pbap Service initSocket");

        boolean initSocketOK = true;
        final int CREATE_RETRY_TIME = 10;

        // It's possible that create will fail in some cases. retry for 10 times
        for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
            try {
                // It is mandatory for PSE to support initiation of bonding and
                // encryption.
                mServerSocket = mAdapter.listenUsingRfcommOn(PORT_NUM);
            } catch (IOException e) {
                Log.e(TAG, "Error create RfcommServerSocket " + e.toString());
                initSocketOK = false;
            }
            if (!initSocketOK) {
                synchronized (this) {
                    try {
                        if (VERBOSE) Log.v(TAG, "wait 3 seconds");
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "socketAcceptThread thread was interrupted (3)");
                        mInterrupted = true;
                    }
                }
            } else {
                break;
            }
        }

        if (initSocketOK) {
            if (VERBOSE) Log.v(TAG, "Succeed to create listening socket on channel " + PORT_NUM);

        } else {
            Log.e(TAG, "Error to create listening socket after " + CREATE_RETRY_TIME + " try");
        }
        return initSocketOK;
    }

    private final void closeSocket(boolean server, boolean accept) throws IOException {
        if (server == true) {
            // Stop the possible trying to init serverSocket
            mInterrupted = true;

            if (mServerSocket != null) {
                mServerSocket.close();
            }
            mServerSocket = null;
        }

        if (accept == true) {
            if (mConnSocket != null) {
                mConnSocket.close();
            }
            mConnSocket = null;
        }
    }

    private final void closeService() {
        if (VERBOSE) Log.v(TAG, "Pbap Service closeService");

        try {
            closeSocket(true, true);
        } catch (IOException ex) {
            Log.e(TAG, "CloseSocket error: " + ex);
        }

        if (mAcceptThread != null) {
            try {
                mAcceptThread.shutdown();
                mAcceptThread.join();
                mAcceptThread = null;
            } catch (InterruptedException ex) {
                Log.w(TAG, "mAcceptThread close error" + ex);
            }
        }
        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        mHasStarted = false;
        if (stopSelfResult(mStartId)) {
            if (VERBOSE) Log.v(TAG, "successfully stopped pbap service");
        }
    }

    private final void startObexServerSession() throws IOException {
        if (VERBOSE) Log.v(TAG, "Pbap Service startObexServerSession");

        // acquire the wakeLock before start Obex transaction thread
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StartingObexPbapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }
        TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            sLocalPhoneNum = tm.getLine1Number();
            sLocalPhoneName = tm.getLine1AlphaTag();
        }
        mPbapServer = new BluetoothPbapObexServer(mSessionStatusHandler);
        synchronized (this) {
            mAuth = new BluetoothPbapAuthenticator(mSessionStatusHandler);
            mAuth.setChallenged(false);
            mAuth.setCancelled(false);
        }
        BluetoothPbapRfcommTransport transport = new BluetoothPbapRfcommTransport(mConnSocket);
        mServerSession = new ServerSession(transport, mPbapServer, mAuth);
        setState(BluetoothPbap.STATE_CONNECTED);
        if (VERBOSE) {
            Log.v(TAG, "startObexServerSession() success!");
        }
    }

    private void stopObexServerSession() {
        if (VERBOSE) Log.v(TAG, "Pbap Service stopObexServerSession");

        // Release the wake lock if obex transaction is over
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        mAcceptThread = null;

        try {
            closeSocket(false, true);
        } catch (IOException e) {
            Log.e(TAG, "closeSocket error: " + e.toString());
        }
        // Last obex transaction is finished, we start to listen for incoming
        // connection again
        if (mAdapter.isEnabled()) {
            startRfcommSocketListener();
        }
        setState(BluetoothPbap.STATE_DISCONNECTED);
    }

    private void notifyAuthKeyInput(final String key) {
        synchronized (this) {
            mAuth.setSessionKey(key);
            mAuth.setChallenged(true);
            mAuth.notify();
        }
    }

    private void notifyAuthCancelled() {
        synchronized (this) {
            mAuth.setCancelled(true);
            mAuth.notify();
        }
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm
     * connect.Once a remote socket connected, this thread shall be
     * shutdown.When the remote disconnect,this thread shall run again waiting
     * for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean stopped = false;

        @Override
        public void run() {
            while (!stopped) {
                try {
                    mConnSocket = mServerSocket.accept();

                    mRemoteDevice = mConnSocket.getRemoteDevice();
                    if (mRemoteDevice != null) {
                        sRemoteDeviceName = mRemoteDevice.getName();
                        // In case getRemoteName failed and return null
                        if (sRemoteDeviceName == null) {
                            sRemoteDeviceName = getString(R.string.defaultname);
                        }
                    }
                    boolean trust = mRemoteDevice.getTrustState();
                    if (VERBOSE) Log.v(TAG, "GetTrustState() = " + trust);

                    if (trust) {
                        try {
                            if (VERBOSE) Log.v(TAG, "incomming connection accepted from: "
                                + sRemoteDeviceName + " automatically as trusted device");
                            startObexServerSession();
                        } catch (IOException ex) {
                            Log.e(TAG, "catch exception starting obex server session"
                                    + ex.toString());
                        }
                    } else {
                        createPbapNotification(ACCESS_REQUEST_ACTION);
                        if (VERBOSE) Log.v(TAG, "incomming connection accepted from: "
                                + sRemoteDeviceName);

                        // In case car kit time out and try to use HFP for
                        // phonebook
                        // access, while UI still there waiting for user to
                        // confirm
                        mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                                .obtainMessage(USER_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                    }
                    stopped = true; // job done ,close this thread;
                } catch (IOException ex) {
                    if (stopped) {
                        break;
                    }
                    if (VERBOSE) Log.v(TAG, "Accept exception: " + ex.toString());
                }
            }
        }

        void shutdown() {
            stopped = true;
            interrupt();
        }
    }

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE) Log.v(TAG, "Handler(): got msg=" + msg.what);

            CharSequence tmpTxt;
            switch (msg.what) {
                case START_LISTENER:
                    if (mAdapter.isEnabled()) {
                        startRfcommSocketListener();
                    } else {
                        closeService();// release all resources
                    }
                    break;
                case USER_TIMEOUT:
                    Intent intent = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
                    sendBroadcast(intent);
                    removePbapNotification(NOTIFICATION_ID_ACCESS);
                    stopObexServerSession();
                    break;
                case AUTH_TIMEOUT:
                    Intent i = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
                    sendBroadcast(i);
                    removePbapNotification(NOTIFICATION_ID_AUTH);
                    notifyAuthCancelled();
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    stopObexServerSession();
                    tmpTxt = getString(R.string.toast_disconnected, sRemoteDeviceName);
                    Toast.makeText(BluetoothPbapService.this, tmpTxt, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SESSION_ESTABLISHED:
                    tmpTxt = getString(R.string.toast_connected, sRemoteDeviceName);
                    Toast.makeText(BluetoothPbapService.this, tmpTxt, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // case MSG_SERVERSESSION_CLOSE will handle ,so just skip
                    break;
                case MSG_OBEX_AUTH_CHALL:
                    createPbapNotification(AUTH_CHALL_ACTION);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                            .obtainMessage(AUTH_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                    break;
                default:
                    break;
            }
        }
    };

    private void setState(int state) {
        setState(state, BluetoothPbap.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DEBUG) Log.d(TAG, "Pbap state " + mState + " -> " + state + ", result = "
                    + result);
            Intent intent = new Intent(BluetoothPbap.PBAP_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothPbap.PBAP_PREVIOUS_STATE, mState);
            mState = state;
            intent.putExtra(BluetoothPbap.PBAP_STATE, mState);
            intent.putExtra(BluetoothIntent.DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_PERM);
        }
    }

    private void createPbapNotification(String action) {
        Context context = getApplicationContext();

        NotificationManager nm = (NotificationManager)context
                .getSystemService(Context.NOTIFICATION_SERVICE);

        // Create an intent triggered by clicking on the status icon.
        Intent clickIntent = new Intent();
        clickIntent.setClass(context, BluetoothPbapActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        clickIntent.setAction(action);

        // Create an intent triggered by clicking on the
        // "Clear All Notifications" button
        Intent deleteIntent = new Intent();
        deleteIntent.setClass(context, BluetoothPbapReceiver.class);

        Notification notification = null;
        String name = getRemoteDeviceName();
        if (action.equals(ACCESS_REQUEST_ACTION)) {
            deleteIntent.setAction(ACCESS_DISALLOWED_ACTION);
            notification = new Notification(android.R.drawable.stat_sys_data_bluetooth, context
                    .getString(R.string.pbap_notif_ticker), System.currentTimeMillis());
            notification.setLatestEventInfo(context, context.getString(R.string.pbap_notif_title),
                    context.getString(R.string.pbap_notif_message, name), PendingIntent
                            .getActivity(context, 0, clickIntent, 0));

            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
            notification.defaults = Notification.DEFAULT_SOUND;
            notification.deleteIntent = PendingIntent.getBroadcast(context, 0, deleteIntent, 0);
            nm.notify(NOTIFICATION_ID_ACCESS, notification);
        } else if (action.equals(AUTH_CHALL_ACTION)) {
            deleteIntent.setAction(AUTH_CANCELLED_ACTION);
            notification = new Notification(android.R.drawable.stat_sys_data_bluetooth, context
                    .getString(R.string.auth_notif_ticker), System.currentTimeMillis());
            notification.setLatestEventInfo(context, context.getString(R.string.auth_notif_title),
                    context.getString(R.string.auth_notif_message, name), PendingIntent
                            .getActivity(context, 0, clickIntent, 0));

            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
            notification.defaults = Notification.DEFAULT_SOUND;
            notification.deleteIntent = PendingIntent.getBroadcast(context, 0, deleteIntent, 0);
            nm.notify(NOTIFICATION_ID_AUTH, notification);
        }
    }

    private void removePbapNotification(int id) {
        Context context = getApplicationContext();
        NotificationManager nm = (NotificationManager)context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(id);
    }

    public static int getPhonebookSize(final int type) {
        int size;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.PHONEBOOK:
                size = sVcardManager.getPhonebookSize();
                break;
            default:
                size = sVcardManager.getCallHistorySize(type);
                break;
        }
        if (VERBOSE) Log.v(TAG, "getPhonebookSzie size = " + size + " type = " + type);
        return size;
    }

    public static void closeContactsCursor(final int type) {
        if (VERBOSE) Log.v(TAG, "closeContactsCursor: type=" + type);

        switch (type) {
            case BluetoothPbapObexServer.ContentType.PHONEBOOK:
                sVcardManager.closeContactsCursor();
                break;
            default:
                sVcardManager.closeCallLogCursor();
                break;
        }
    }

    public static int queryDataFromContactsDB(final int type) {
        if (VERBOSE) Log.v(TAG, "queryDataFromContactsDB: type=" + type);

        int queryResult = 0;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.PHONEBOOK:
                queryResult = sVcardManager.queryDataFromContactsDB();
                break;
            default:
                queryResult = sVcardManager.queryDataFromCallLogDB(type);
                break;
        }
        return queryResult;
    }

    public static String getPhonebook(final int type, final int pos, final boolean vcardType) {
        if (VERBOSE) Log.v(TAG, "getPhonebook type=" + type + " pos=" + pos + " vcardType="
                + vcardType);

        String vCardStr;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.PHONEBOOK:
                vCardStr = sVcardManager.getPhonebook(pos, vcardType);
                break;
            default:
                vCardStr = sVcardManager.getCallHistory(pos, vcardType);
                break;
        }
        return vCardStr;
    }

    public static String getLocalPhoneNum() {
        return sLocalPhoneNum;
    }

    public static String getLocalPhoneName() {
        return sLocalPhoneName;
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    // Used for phone book listing by name
    public static ArrayList<String> getPhonebookNameList() {
        return sVcardManager.loadNameList();
    }

    // Used for phone book listing by number
    public static ArrayList<String> getPhonebookNumberList() {
        return sVcardManager.loadNumberList();
    }

    // Used for call history listing
    public static ArrayList<String> getCallLogList(final int type) {
        return sVcardManager.loadCallHistoryList(type);
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothPbap.Stub mBinder = new IBluetoothPbap.Stub() {
        public int getState() {
            if (DEBUG) Log.d(TAG, "getState " + mState);

            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState;
        }

        public BluetoothDevice getClient() {
            if (DEBUG) Log.d(TAG, "getClient" + mRemoteDevice);

            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mState == BluetoothPbap.STATE_DISCONNECTED) {
                return null;
            }
            return mRemoteDevice;
        }

        public boolean isConnected(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothPbap.STATE_CONNECTED && mRemoteDevice.equals(device);
        }

        public boolean connect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            return false;
        }

        public void disconnect() {
            if (DEBUG) Log.d(TAG, "disconnect");

            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothPbapService.this) {
                switch (mState) {
                    case BluetoothPbap.STATE_CONNECTED:
                        if (mServerSession != null) {
                            mServerSession.close();
                            mServerSession = null;
                        }
                        try {
                            closeSocket(false, true);
                        } catch (IOException ex) {
                            Log.e(TAG, "Caught the error: " + ex);
                        }
                        setState(BluetoothPbap.STATE_DISCONNECTED, BluetoothPbap.RESULT_CANCELED);
                        break;
                    default:
                        break;
                }
            }
        }
    };
}