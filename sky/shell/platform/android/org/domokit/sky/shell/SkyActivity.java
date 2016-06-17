// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.domokit.sky.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.IntentFilter;
import android.content.Context;
import android.content.BroadcastReceiver;


import io.flutter.view.FlutterMain;
import io.flutter.view.FlutterView;

import org.chromium.base.PathUtils;
import org.chromium.base.TraceEvent;
import org.chromium.mojom.sky.EventType;
import org.chromium.mojom.sky.InputEvent;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for activities that use Sky.
 */
public class SkyActivity extends Activity {
    private FlutterView mView;

    IntentMessenger mMessenger;

    private String[] getArgsFromIntent(Intent intent) {
        // Before adding more entries to this list, consider that arbitrary
        // Android applications can generate intents with extra data and that
        // there are many security-sensitive args in the binary.
        ArrayList<String> args = new ArrayList<String>();
        if (intent.getBooleanExtra("enable-checked-mode", false)) {
            args.add("--enable-checked-mode");
        }
        if (intent.getBooleanExtra("trace-startup", false)) {
            args.add("--trace-startup");
        }
        if (intent.getBooleanExtra("start-paused", false)) {
            args.add("--start-paused");
        }
        if (!args.isEmpty()) {
            String[] argsArray = new String[args.size()];
            return args.toArray(argsArray);
        }
        return null;
    }

    /**
     * @see android.app.Activity#onCreate(android.os.Bundle)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(0x40000000);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        String[] args = getArgsFromIntent(getIntent());
        FlutterMain.ensureInitializationComplete(getApplicationContext(), args);
        mView = new FlutterView(this);
        setContentView(mView);

        onSkyReady();

        mMessenger = new IntentMessenger(this);
        mMessenger.setOnMessageListener(new IntentMessenger.OnMessageListener() {
            @Override
            public void onMessage(String event, String data) {
                mView.sendToFlutter(event, data);
            }

            @Override
            public void onQuery(String event, String data, IntentMessenger.MessageCallback response) {
                final IntentMessenger.MessageCallback messengerCallback = response;
                mView.sendToFlutter(event, data, new FlutterView.MessageReplyCallback() {
                    @Override
                    public void onReply(String s) {
                        messengerCallback.onResponse(s);
                    }
                });
            }
        });

        mMessenger.start();

        mView.addOnMessageListener("sendMessage", new FlutterView.OnMessageListener() {
            @Override
            public String onMessage(String s) {
                if(s == null) return null;

                String targetPackage = null;
                String event = null;
                String data = null;
                try {
                    JSONObject params = new JSONObject(s);
                    targetPackage = params.getString("targetPackage");
                    event = params.getString("event");
                    data = params.getString("data");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return null;
                }

                mMessenger.send(targetPackage, event, data);
                return null;
            }
        });

        //TODO: add unsubscribe
        mView.addOnMessageListenerAsync("sendQuery", new FlutterView.OnMessageListenerAsync() {
            @Override
            public void onMessage(String s, FlutterView.MessageResponse messageResponse) {
                if(s == null) return;

                String targetPackage = null;
                String event = null;
                String data = null;
                final boolean subscribe;
                try {
                    JSONObject params = new JSONObject(s);
                    targetPackage = params.getString("targetPackage");
                    event = params.getString("event");
                    data = params.getString("data");
                    subscribe = params.getBoolean("subscribe");
                } catch (JSONException e) {
                    e.printStackTrace();
                    return;
                }

                final FlutterView.MessageResponse response = messageResponse;
                mMessenger.send(targetPackage, event, data, new IntentMessenger.MessageCallback() {
                    @Override
                    public boolean onResponse(String data) {
                        response.send(data);
                        return subscribe;
                    }
                });
                return;
            }
        });

    }

    /**
     * @see android.app.Activity#onDestroy()
     */
    @Override
    protected void onDestroy() {
        if (mView != null) {
            mView.destroy();
        }
        if(mMessenger != null){
            mMessenger.stop();
        }
        // Do we need to shut down Sky too?
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mView != null) {
            mView.popRoute();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mView != null) {
            mView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mView != null) {
            mView.onResume();
        }
        if(mMessenger != null){
            mMessenger.start();
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (mView != null) {
            mView.onPostResume();
        }
    }

    /**
      * Override this function to customize startup behavior.
      */
    protected void onSkyReady() {
        TraceEvent.instant("SkyActivity.onSkyReady");

        if (loadIntent(getIntent())) {
            return;
        }
        File dataDir = new File(PathUtils.getDataDirectory(this));
        File appBundle = new File(dataDir, FlutterMain.APP_BUNDLE);
        if (appBundle.exists()) {
            mView.runFromBundle(appBundle.getPath(), null);
            return;
        }
    }

    protected void onNewIntent(Intent intent) {
        loadIntent(intent);
    }

    public boolean loadIntent(Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_RUN.equals(action)) {
            mView.runFromBundle(intent.getDataString(),
                                intent.getStringExtra("snapshot"));
            String route = intent.getStringExtra("route");
            if (route != null)
                mView.pushRoute(route);
            return true;
        }

        return false;
    }
}

class IntentMessenger {
    static final String ACTION_QUERY = "messenger.action.query";
    static final String ACTION_MESSAGE = "messenger.action.message";
    static final String ACTION_RESPONSE = "messenger.action.response";

    static final String EXTRA_EVENT = "messenger.extra.event";
    static final String EXTRA_DATA = "messenger.extra.msg";
    static final String EXTRA_ID = "messenger.extra.id";
    static final String EXTRA_PACKAGE = "messenger.extra.package";

    private Context mContext;
    private String mPackageName;

    private boolean mRegistered;

    private IntentFilter mQueryIntentFilter;
    private IntentFilter mMessageIntentFilter;
    private IntentFilter mResponseIntentFilter;

    //TODO: timeouts, etc.
    private Map<String, MessageCallback> mCallbacks = new HashMap<>();

    private OnMessageListener mMessageListener;

    public interface MessageCallback{
        boolean onResponse(String data);
    }

    public interface OnMessageListener {
        void onMessage(String event, String data);
        void onQuery(String event, String data, MessageCallback response);
    }


    public IntentMessenger(Context context){
        if(context == null){
            throw new RuntimeException("Context argument is null");
        }
        mContext = context;
        mPackageName = mContext.getPackageName();
        mQueryIntentFilter = new IntentFilter(mPackageName + "." + ACTION_QUERY);
        mMessageIntentFilter = new IntentFilter(mPackageName + "." + ACTION_MESSAGE);
        mResponseIntentFilter = new IntentFilter(mPackageName + "." + ACTION_RESPONSE);
    }

    public void setOnMessageListener(OnMessageListener mMessageListener) {
        this.mMessageListener = mMessageListener;
    }

    private BroadcastReceiver mQueryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if(mMessageListener == null) return;

            String event = intent.getStringExtra(EXTRA_EVENT);
            if(event == null) return;   //invalid message

            final String id = intent.getStringExtra(EXTRA_ID);
            if(id == null) return;   //invalid message

            String data = intent.getStringExtra(EXTRA_DATA);

            final String sourcePackage = intent.getStringExtra(EXTRA_PACKAGE);

            mMessageListener.onQuery(event, data, new MessageCallback() {
                @Override
                public boolean onResponse(String response) {
                    Intent responseIntent = new Intent(sourcePackage + "." + ACTION_RESPONSE);
                    responseIntent.putExtra(EXTRA_ID, id);
                    responseIntent.putExtra(EXTRA_DATA, response);
                    mContext.sendBroadcast(responseIntent);
                    return false;
                }
            });
        }
    };

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(mMessageListener == null) return;

            String event = intent.getStringExtra(EXTRA_EVENT);
            if(event == null) return;   //invalid message
            String msg = intent.getStringExtra(EXTRA_DATA);

            mMessageListener.onMessage(event, msg);
        }
    };

    private BroadcastReceiver mResponseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(EXTRA_ID);
            if(!mCallbacks.containsKey(id)) return;

            MessageCallback callback = mCallbacks.get(id);

            String data = intent.getStringExtra(EXTRA_DATA);

            //if the response returns false, remove the callback.
            if(!callback.onResponse(data)){
                mCallbacks.remove(id);
            }
        }
    };

    public void removeCallback(String id){
        if(mCallbacks.containsKey(id)){
            mCallbacks.remove(id);
        }
    }

    public void start(){
        if(!mRegistered){
            mContext.registerReceiver(mMessageReceiver, mMessageIntentFilter);
            mContext.registerReceiver(mQueryReceiver, mQueryIntentFilter);
            mContext.registerReceiver(mResponseReceiver, mResponseIntentFilter);
            mRegistered = true;
        }
    }

    public void stop(){
        if(mRegistered){
            mRegistered = false;
            try {
                mContext.unregisterReceiver(mMessageReceiver);
                mContext.unregisterReceiver(mQueryReceiver);
                mContext.unregisterReceiver(mResponseReceiver);
            } catch(IllegalArgumentException e){
                e.printStackTrace();
            }
        }
    }

    public void send(String packageName, String event, String data){
        Intent intent = new Intent(packageName + "." + ACTION_MESSAGE);
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_DATA, data);
        intent.putExtra(EXTRA_PACKAGE, mPackageName);
        mContext.sendBroadcast(intent);
    }

    public void send(String packageName, String event, String data, MessageCallback callback){
        String id = UUID.randomUUID().toString();
        mCallbacks.put(id, callback);
        Intent intent = new Intent(packageName + "." + ACTION_QUERY);
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_DATA, data);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_PACKAGE, mPackageName);
        mContext.sendBroadcast(intent);
    }
}
