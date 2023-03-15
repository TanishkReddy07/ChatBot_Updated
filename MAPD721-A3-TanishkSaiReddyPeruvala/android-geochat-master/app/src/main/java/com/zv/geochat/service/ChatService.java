package com.zv.geochat.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.zv.geochat.Constants;
import com.zv.geochat.R;
import com.zv.geochat.broadcast.BroadcastSender;
import com.zv.geochat.connection.ChatEventHandler;
import com.zv.geochat.connection.ConnectionManager;
import com.zv.geochat.model.ChatMessage;
import com.zv.geochat.model.ChatMessageBody;
import com.zv.geochat.notification.NotificationDecorator;
import com.zv.geochat.provider.ChatMessageStore;

public class ChatService extends Service {
    private static final String TAG = "ChatService";

    public static final String CMD = "cmd";
    public static final int CMD_JOIN_CHAT = 10;
    public static final int CMD_LEAVE_CHAT = 20;
    public static final int CMD_SEND_MESSAGE = 30;
    public static final String KEY_MESSAGE_TEXT = "message_text";

    private int messageCounterValue;
    private NotificationManager notificationMgr;
    private PowerManager.WakeLock wakeLock;
    private ConnectionManager connectionManager;

    private String myName;
    private String serverUri;

    public ChatService() {
    }

    @Override
    public void onCreate() {
        Log.v(TAG, "onCreate()");
        super.onCreate();

        // create preference listener to get message counter preference value
        SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (key.equals(Constants.PREF_KEY_MESSAGE_COUNTER)) {
                    messageCounterValue = Integer.parseInt(sharedPreferences.getString(Constants.PREF_KEY_MESSAGE_COUNTER, "2"));
                }
            }
        };

        // register preference listener
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        // load preferences including message counter preference value
        loadPreferences();

        notificationMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationDecorator notificationDecorator = new NotificationDecorator(this, notificationMgr);
        ChatEventHandler chatEventHandler = new ChatEventHandler(new BroadcastSender(this),
                new ChatMessageStore(this), notificationDecorator);
        connectionManager = new ConnectionManager(this, chatEventHandler);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand()");
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            Bundle data = intent.getExtras();
            handleData(data);
            if (!wakeLock.isHeld()) {
                Log.v(TAG, "acquiring wake lock");
                wakeLock.acquire();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy()");
        notificationMgr.cancelAll();
        Log.v(TAG, "releasing wake lock");
        wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public int getResponseCode() {
        return 0;
    }

    public class ChatServiceBinder extends Binder {
        public ChatService getService() {
            return ChatService.this;
        }
    }


    private void handleData(Bundle data) {
        int command = data.getInt(CMD);
        Log.d(TAG, "-(<- received command: command=" + command);
        if (command == CMD_JOIN_CHAT) {
            if(connectionManager.isConnected()){ // reconnect if already connected
                connectionManager.disconnectFromServer();
            }
            connectionManager.connectToServer(serverUri, myName);
        } else if (command == CMD_LEAVE_CHAT) {
            connectionManager.leaveChat(myName);
            connectionManager.disconnectFromServer();
            stopSelf();
        } else if (command == CMD_SEND_MESSAGE) {
            String message = (String) data.get(KEY_MESSAGE_TEXT);
            connectionManager.attemptSend(myName, message);
        } else {
            Log.w(TAG, "Ignoring Unknown Command! cmd=" + command);
        }
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        myName = prefs.getString(Constants.PREF_KEY_USER_NAME, "Default Name");
        serverUri = prefs.getString(Constants.PREF_KEY_SERVER_URI, null);
        messageCounterValue = Integer.parseInt(prefs.getString(Constants.PREF_KEY_MESSAGE_COUNTER, "2"));
    }
    public void sendSessionClosedBroadcast(int sessionMessageLimit) {
        String message = "Session closed after reaching the limit: " + sessionMessageLimit + " messages";
        Intent intent = new Intent(Constants.BROADCAST_NEW_MESSAGE);
        intent.putExtra(Constants.CHAT_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
