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
 * limitations under the License.
 */

package com.android.phone.testapps.imstestapp;

import android.os.RemoteException;
import android.telephony.ims.stub.ImsConfigImplBase;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;

public class TestImsConfigImpl extends ImsConfigImplBase {

    @Override
    public int getProvisionedValue(int item) throws RemoteException {
        return ImsConfig.FeatureValueConstants.ON;
    }

    @Override
    public String getProvisionedStringValue(int item) throws RemoteException {
        return null;
    }

    @Override
    public int setProvisionedValue(int item, int value) throws RemoteException {
        return ImsConfig.OperationStatusConstants.SUCCESS;
    }

    @Override
    public int setProvisionedStringValue(int item, String value) throws RemoteException {
        return ImsConfig.OperationStatusConstants.SUCCESS;
    }

    @Override
    public void getFeatureValue(int feature, int network, ImsConfigListener listener)
            throws RemoteException {
        listener.onGetFeatureResponse(feature, network, ImsConfig.FeatureValueConstants.ON,
                ImsConfig.OperationStatusConstants.SUCCESS);
    }

    @Override
    public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener)
            throws RemoteException {
        listener.onSetFeatureResponse(feature, network, value,
                ImsConfig.OperationStatusConstants.SUCCESS);
    }
}
