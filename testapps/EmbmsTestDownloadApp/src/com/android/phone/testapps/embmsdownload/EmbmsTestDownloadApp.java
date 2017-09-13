/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone.testapps.embmsdownload;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.telephony.MbmsDownloadManager;
import android.telephony.SubscriptionManager;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.FileServiceInfo;
import android.telephony.mbms.MbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsException;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EmbmsTestDownloadApp extends Activity {
    private static final String LOG_TAG = "EmbmsDownloadApp";

    public static final String DOWNLOAD_DONE_ACTION =
            "com.android.phone.testapps.embmsdownload.DOWNLOAD_DONE";

    private static final String CUSTOM_EMBMS_TEMP_FILE_LOCATION = "customEmbmsTempFiles";

    private static final String FILE_AUTHORITY = "com.android.phone.testapps";
    private static final String FILE_DOWNLOAD_SCHEME = "filedownload";

    private static EmbmsTestDownloadApp sInstance;

    private static final class ImageAdapter
            extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {
        static class ImageViewHolder extends RecyclerView.ViewHolder {
            public ImageView imageView;
            public ImageViewHolder(ImageView view) {
                super(view);
                imageView = view;
            }
        }

        private final List<Uri> mImageUris = new ArrayList<>();

        @Override
        public ImageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView view = new ImageView(parent.getContext());
            view.setAdjustViewBounds(true);
            view.setMaxHeight(500);
            return new ImageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ImageViewHolder holder, int position) {
            holder.imageView.setImageURI(mImageUris.get(position));
        }

        @Override
        public int getItemCount() {
            return mImageUris.size();
        }

        public void addImage(Uri uri) {
            mImageUris.add(uri);
            notifyDataSetChanged();
        }
    }

    private final class FileServiceInfoAdapter
            extends ArrayAdapter<FileServiceInfo> {
        public FileServiceInfoAdapter(Context context) {
            super(context, android.R.layout.simple_spinner_item);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FileServiceInfo info = getItem(position);
            TextView result = new TextView(EmbmsTestDownloadApp.this);
            result.setText(info.getNames().get(info.getLocales().get(0)));
            return result;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            FileServiceInfo info = getItem(position);
            TextView result = new TextView(EmbmsTestDownloadApp.this);
            String text = "name="
                    + info.getNames().get(info.getLocales().get(0))
                    + ", "
                    + "numFiles="
                    + info.getFiles().size();
            result.setText(text);
            return result;
        }

        public void update(List<FileServiceInfo> services) {
            clear();
            addAll(services);
        }
    }

    private final class DownloadRequestAdapter
            extends ArrayAdapter<DownloadRequest> {
        public DownloadRequestAdapter(Context context) {
            super(context, android.R.layout.simple_spinner_item);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DownloadRequest request = getItem(position);
            TextView result = new TextView(EmbmsTestDownloadApp.this);
            result.setText(request.getSourceUri().toSafeString());
            return result;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getView(position, convertView, parent);
        }
    }


    private MbmsDownloadManagerCallback mCallback = new MbmsDownloadManagerCallback() {
        @Override
        public void onError(int errorCode, String message) {
            runOnUiThread(() -> Toast.makeText(EmbmsTestDownloadApp.this,
                    "Error " + errorCode + ": " + message, Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onFileServicesUpdated(List<FileServiceInfo> services) {
            EmbmsTestDownloadApp.this.runOnUiThread(() ->
                    Toast.makeText(EmbmsTestDownloadApp.this,
                            "Got services length " + services.size(),
                            Toast.LENGTH_SHORT).show());
            updateFileServicesList(services);
        }

        @Override
        public void onMiddlewareReady() {
            runOnUiThread(() -> Toast.makeText(EmbmsTestDownloadApp.this,
                    "Initialization done", Toast.LENGTH_SHORT).show());
        }
    };

    private MbmsDownloadManager mDownloadManager;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private FileServiceInfoAdapter mFileServiceInfoAdapter;
    private DownloadRequestAdapter mDownloadRequestAdapter;
    private ImageAdapter mImageAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sInstance = this;
        mHandlerThread = new HandlerThread("EmbmsDownloadWorker");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mFileServiceInfoAdapter = new FileServiceInfoAdapter(this);
        mDownloadRequestAdapter = new DownloadRequestAdapter(this);

        RecyclerView downloadedImages = (RecyclerView) findViewById(R.id.downloaded_images);
        downloadedImages.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mImageAdapter = new ImageAdapter();
        downloadedImages.setAdapter(mImageAdapter);

        Button bindButton = (Button) findViewById(R.id.bind_button);
        bindButton.setOnClickListener((view) -> {
            try {
                mDownloadManager = MbmsDownloadManager.create(this, mCallback);
            } catch (MbmsException e) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show();
            }
        });

        Button setTempFileRootButton = (Button) findViewById(R.id.set_temp_root_button);
        setTempFileRootButton.setOnClickListener((view) -> {
            File downloadDir = new File(EmbmsTestDownloadApp.this.getFilesDir(),
                    CUSTOM_EMBMS_TEMP_FILE_LOCATION);
            downloadDir.mkdirs();
            try {
                mDownloadManager.setTempFileRootDirectory(downloadDir);
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "temp file root set to " + downloadDir, Toast.LENGTH_SHORT).show();
            } catch (MbmsException e) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show();
            }
        });

        Button getFileServicesButton = (Button) findViewById(R.id.get_file_services_button);
        getFileServicesButton.setOnClickListener((view) -> mHandler.post(() -> {
            try {
                mDownloadManager.getFileServices(Collections.singletonList("Class1"));
            } catch (MbmsException e) {
                runOnUiThread(() -> Toast.makeText(EmbmsTestDownloadApp.this,
                        "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show());
            }
        }));

        final Spinner serviceSelector = (Spinner) findViewById(R.id.available_file_services);
        serviceSelector.setAdapter(mFileServiceInfoAdapter);

        Button requestDlButton = (Button) findViewById(R.id.request_dl_button);
        requestDlButton.setOnClickListener((view) ->  {
            if (mDownloadManager == null) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "No download service bound", Toast.LENGTH_SHORT).show();
                return;
            }
            FileServiceInfo serviceInfo =
                    (FileServiceInfo) serviceSelector.getSelectedItem();
            if (serviceInfo == null) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "No file service selected", Toast.LENGTH_SHORT).show();
                return;
            }

            performDownload(serviceInfo);
        });

        Button requestCleanupButton = (Button) findViewById(R.id.request_cleanup_button);
        requestCleanupButton.setOnClickListener((view) ->
                SideChannel.triggerCleanup(EmbmsTestDownloadApp.this));

        Button requestSpuriousTempFilesButton =
                (Button) findViewById(R.id.request_spurious_temp_files_button);
        requestSpuriousTempFilesButton.setOnClickListener((view) ->
                SideChannel.requestSpuriousTempFiles(EmbmsTestDownloadApp.this,
                        (FileServiceInfo) serviceSelector.getSelectedItem()));

        NumberPicker downloadDelayPicker = (NumberPicker) findViewById(R.id.delay_factor);
        downloadDelayPicker.setMinValue(1);
        downloadDelayPicker.setMaxValue(50);

        Button delayDownloadButton = (Button) findViewById(R.id.delay_download_button);
        delayDownloadButton.setOnClickListener((view) ->
                SideChannel.delayDownloads(EmbmsTestDownloadApp.this,
                        downloadDelayPicker.getValue()));

        final Spinner downloadRequestSpinner = (Spinner) findViewById(R.id.active_downloads);
        downloadRequestSpinner.setAdapter(mDownloadRequestAdapter);

        Button cancelDownloadButton = (Button) findViewById(R.id.cancel_download_button);
        cancelDownloadButton.setOnClickListener((view) -> {
            if (mDownloadManager == null) {
                Toast.makeText(EmbmsTestDownloadApp.this,
                        "No download service bound", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                DownloadRequest request =
                        (DownloadRequest) downloadRequestSpinner.getSelectedItem();
                mDownloadManager.cancelDownload(request);
                mDownloadRequestAdapter.remove(request);
            } catch (MbmsException e) {
                runOnUiThread(() -> Toast.makeText(EmbmsTestDownloadApp.this,
                        "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandlerThread.quit();
        sInstance = null;
    }

    public static EmbmsTestDownloadApp getInstance() {
        return sInstance;
    }

    public void onDownloadFailed(int result) {
        runOnUiThread(() ->
                Toast.makeText(this, "Download failed: " + result, Toast.LENGTH_SHORT).show());
    }

    // TODO: assumes that process does not get killed. Replace with more robust alternative
    public void onDownloadDone(Uri fileLocation) {
        Log.i(LOG_TAG, "File completed: " + fileLocation);
        File imageFile = new File(fileLocation.getPath());
        if (!imageFile.exists()) {
            Toast.makeText(this, "Download done but destination doesn't exist", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        mImageAdapter.addImage(fileLocation);
    }

    private void updateFileServicesList(List<FileServiceInfo> services) {
        runOnUiThread(() -> mFileServiceInfoAdapter.update(services));
    }

    private void performDownload(FileServiceInfo info) {
        File destination = null;
        Uri.Builder sourceUriBuilder = new Uri.Builder()
                .scheme(FILE_DOWNLOAD_SCHEME)
                .authority(FILE_AUTHORITY);
        try {
            if (info.getFiles().size() > 1) {
                destination = new File(getFilesDir(), "images/animals/").getCanonicalFile();
                destination.mkdirs();
                clearDirectory(destination);
                sourceUriBuilder.path("/*");
            } else {
                destination = new File(getFilesDir(), "images/image.png").getCanonicalFile();
                destination.delete();
                sourceUriBuilder.path("/image.png");
            }
        } catch (IOException e) {
            // ignore
        }

        Intent completionIntent = new Intent(DOWNLOAD_DONE_ACTION);
        completionIntent.setClass(this, DownloadCompletionReceiver.class);

        DownloadRequest request = new DownloadRequest.Builder()
                .setServiceInfo(info)
                .setSource(sourceUriBuilder.build())
                .setDest(Uri.fromFile(destination))
                .setAppIntent(completionIntent)
                .setSubscriptionId(SubscriptionManager.getDefaultSubscriptionId())
                .build();

        try {
            mDownloadManager.download(request, null, null);
            mDownloadRequestAdapter.add(request);
        } catch (MbmsException e) {
            Toast.makeText(EmbmsTestDownloadApp.this,
                    "caught MbmsException: " + e.getErrorCode(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void clearDirectory(File directory) {
        for (File file: directory.listFiles()) {
            if (file.isDirectory()) {
                clearDirectory(file);
            }
            file.delete();
        }
    }
}
