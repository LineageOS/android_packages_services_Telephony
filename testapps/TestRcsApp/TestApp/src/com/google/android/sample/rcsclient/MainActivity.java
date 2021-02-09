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
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/** An activity to show function list. */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TestRcsApp.MainActivity";
    private Button mProvisionButton;
    private Button mDelegateButton;
    private Button mUceButton;
    private Button mGbaButton;
    private Button mMessageClientButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        mProvisionButton = (Button) this.findViewById(R.id.provision);
        mDelegateButton = (Button) this.findViewById(R.id.delegate);
        mMessageClientButton = (Button) this.findViewById(R.id.msgClient);
        mUceButton = (Button) this.findViewById(R.id.uce);
        mGbaButton = (Button) this.findViewById(R.id.gba);
        mProvisionButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, ProvisioningActivity.class);
            MainActivity.this.startActivity(intent);
        });

        mDelegateButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, DelegateActivity.class);
            MainActivity.this.startActivity(intent);
        });

        mUceButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, UceActivity.class);
            MainActivity.this.startActivity(intent);
        });

        mGbaButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, GbaActivity.class);
            MainActivity.this.startActivity(intent);
        });
        mMessageClientButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, ContactListActivity.class);
            MainActivity.this.startActivity(intent);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }
}

