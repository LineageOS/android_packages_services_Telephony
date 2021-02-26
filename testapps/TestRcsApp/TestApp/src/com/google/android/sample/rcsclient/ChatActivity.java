/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.google.android.sample.rcsclient;

import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.sample.rcsclient.util.ChatManager;
import com.google.android.sample.rcsclient.util.ChatProvider;
import com.google.android.sample.rcsclient.util.NumberUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An activity to show chat message with specific number. */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_REMOTE_PHONE_NUMBER = "REMOTE_PHONE_NUMBER";
    public static final String TELURI_PREFIX = "tel:";
    private static final String TAG = "TestRcsApp.ChatActivity";
    private static final int INIT_LIST = 1;
    private static final int SHOW_TOAST = 2;
    private static final float TEXT_SIZE = 20.0f;
    private static final int MARGIN_SIZE = 20;
    private final ExecutorService mFixedThreadPool = Executors.newFixedThreadPool(3);
    private boolean mSessionInitResult = false;
    private Button mSend;
    private String mDestNumber;
    private TextView mDestNumberView;
    private EditText mNewMessage;
    private ChatObserver mChatObserver;
    private Handler mHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        setContentView(R.layout.chat_layout);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                Log.d(TAG, "handleMessage:" + msg.what);
                switch (msg.what) {
                    case INIT_LIST:
                        initChatMessageLayout((Cursor) msg.obj);
                        break;
                    case SHOW_TOAST:
                        Toast.makeText(ChatActivity.this, msg.obj.toString(),
                                Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        Log.d(TAG, "unknown msg:" + msg.what);
                        break;
                }

            }
        };
        mDestNumberView = findViewById(R.id.destNum);
        initDestNumber();
        mChatObserver = new ChatObserver(mHandler);
    }

    private void initDestNumber() {
        Intent intent = getIntent();
        mDestNumber = intent.getStringExtra(EXTRA_REMOTE_PHONE_NUMBER);
        mDestNumberView.setText(mDestNumber);
    }

    @Override
    protected void onStart() {
        super.onStart();
        initChatButton();
        queryChatData();
        getContentResolver().registerContentObserver(ChatProvider.CHAT_URI, false,
                mChatObserver);
    }

    private void initChatButton() {
        mNewMessage = findViewById(R.id.new_msg);
        mSend = findViewById(R.id.chat_btn);

        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.e(TAG, "invalid subId:" + subId);
            return;
        }
        try {
            // Reformat so that the number matches the one sent to the network.
            String formattedNumber = NumberUtils.formatNumber(this, mDestNumber);
            if (formattedNumber != null) {
                mDestNumber = formattedNumber;
            }
            mDestNumberView.setText(mDestNumber);
            ChatManager.getInstance(getApplicationContext(), subId).initChatSession(
                    TELURI_PREFIX + mDestNumber, new SessionStateCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "session init succeeded");
                            mHandler.sendMessage(mHandler.obtainMessage(SHOW_TOAST,
                                    ChatActivity.this.getResources().getString(
                                            R.string.session_succeeded)));
                            mSessionInitResult = true;
                        }

                        @Override
                        public void onFailure() {
                            Log.i(TAG, "session init failed");
                            mHandler.sendMessage(mHandler.obtainMessage(SHOW_TOAST,
                                    ChatActivity.this.getResources().getString(
                                            R.string.session_failed)));
                            mSessionInitResult = false;
                        }
                    });

            mSend.setOnClickListener(view -> {
                if (!mSessionInitResult) {
                    Toast.makeText(ChatActivity.this,
                            getResources().getString(R.string.session_not_ready),
                            Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "session not ready");
                    return;
                }
                mFixedThreadPool.execute(() -> {
                    if (TextUtils.isEmpty(mDestNumber)) {
                        Log.i(TAG, "Destination number is empty");
                    } else {
                        ChatManager.getInstance(getApplicationContext(), subId).addNewMessage(
                                mNewMessage.getText().toString(), ChatManager.SELF, mDestNumber);
                        ChatManager.getInstance(getApplicationContext(), subId).sendMessage(
                                TELURI_PREFIX + mDestNumber, mNewMessage.getText().toString());
                    }
                });
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            Toast.makeText(this, getResources().getString(R.string.session_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void initChatMessageLayout(Cursor cursor) {
        Log.i(TAG, "initChatMessageLayout");
        RelativeLayout rl = findViewById(R.id.relative_layout);
        int id = 1;
        if (cursor != null && cursor.moveToNext()) {
            TextView chatMessage = initChatMessageItem(cursor, id++, true);
            rl.addView(chatMessage);
        }
        while (cursor != null && cursor.moveToNext()) {
            TextView chatMessage = initChatMessageItem(cursor, id++, false);
            rl.addView(chatMessage);
        }
        if (cursor != null) {
            cursor.close();
        }
    }

    private TextView initChatMessageItem(Cursor cursor, int id, boolean isFirst) {
        TextView chatMsg = new TextView(this);
        chatMsg.setId(id);
        chatMsg.setText(
                cursor.getString(cursor.getColumnIndex(ChatProvider.RcsColumns.CHAT_MESSAGE)));
        chatMsg.setTextSize(TEXT_SIZE);
        chatMsg.setTypeface(null, Typeface.BOLD);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, MARGIN_SIZE, 0, 0);
        if (messageFromSelf(cursor)) {
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            chatMsg.setBackgroundColor(Color.YELLOW);
        } else {
            lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            chatMsg.setBackgroundColor(Color.LTGRAY);
        }
        if (!isFirst) {
            lp.addRule(RelativeLayout.BELOW, id - 1);
        }
        chatMsg.setLayoutParams(lp);
        return chatMsg;
    }

    private boolean messageFromSelf(Cursor cursor) {
        return ChatManager.SELF.equals(
                cursor.getString(cursor.getColumnIndex(ChatProvider.RcsColumns.SRC_PHONE_NUMBER)));
    }

    private void queryChatData() {
        mFixedThreadPool.execute(() -> {
            Cursor cursor = getContentResolver().query(ChatProvider.CHAT_URI,
                    new String[]{ChatProvider.RcsColumns.SRC_PHONE_NUMBER,
                            ChatProvider.RcsColumns.DEST_PHONE_NUMBER,
                            ChatProvider.RcsColumns.CHAT_MESSAGE},
                    ChatProvider.RcsColumns.SRC_PHONE_NUMBER + "=? OR "
                            + ChatProvider.RcsColumns.DEST_PHONE_NUMBER + "=?",
                    new String[]{mDestNumber, mDestNumber},
                    ChatProvider.RcsColumns.MSG_TIMESTAMP + " ASC");
            mHandler.sendMessage(mHandler.obtainMessage(INIT_LIST, cursor));
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
        getContentResolver().unregisterContentObserver(mChatObserver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }

    private void dispose() {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.e(TAG, "invalid subId:" + subId);
            return;
        }
        ChatManager chatManager = ChatManager.getInstance(this, subId);
        chatManager.deregister();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    private class ChatObserver extends ContentObserver {
        ChatObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            Log.i(TAG, "onChange");
            queryChatData();
        }
    }

}
