// (c) 2104 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.megster.cordova.ble.central;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.os.Build.VERSION;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.telecom.Call;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.*;


public class BLECentralPlugin extends CordovaPlugin implements BluetoothAdapter.LeScanCallback, PeripheralCallback {

    // actions
    private static final String START_SCAN = "startScan";
    private static final String SCAN = "scan";
    private static final String STOP_SCAN = "stopScan";
    private static final String CONNECT = "connect";
    private static final String DISCONNECT = "disconnect";
    private static final String WRITE = "write";
    private static final String START_NOTIFICATION = "startNotification"; // register for characteristic notification
    private static final String ENABLE = "enable";

    // callbacks
    CallbackContext discoverCallback;

    private enum States {
        ERROR, IDLE, SCANNING, CONNECTED;
    };

    private States activeState = States.IDLE;

    private static final String TAG = "BLEPlugin";
    private static final int REQUEST_ENABLE_BLUETOOTH = 1;

    BluetoothAdapter bluetoothAdapter;
    BluetoothManager bluetoothManager;

    Peripheral activePeripheral;

    @Override
    public void onPeripheralChange() {
        Log.d(TAG, "I've been called");
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "action = " + action);

        // Tells us whether we are currently scanning
        UUID[] serviceUUIDs;
        UUID serviceUUID;
        UUID characteristicUUID;
        String macAddress;

        if (bluetoothAdapter == null) {
            Activity activity = cordova.getActivity();
            bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        Log.d(TAG, "NUM CONNECTED DEVICES: " + devices.size());


        switch (action) {
            case START_SCAN:
            case SCAN:
                serviceUUIDs = parseServiceUUIDList(args.getJSONArray(0));
                findLowEnergyDevices(callbackContext, serviceUUIDs);
                break;
            case STOP_SCAN:
                bluetoothAdapter.stopLeScan(this);
                callbackContext.success();
                activeState = States.IDLE;
                break;
            case CONNECT:
                macAddress = args.getString(0);
                connect(callbackContext, macAddress);
                activeState = States.CONNECTED;
                break;
            case DISCONNECT:
                close(callbackContext);
                activeState = States.IDLE;
                break;
            case WRITE:
                macAddress = args.getString(0);
                serviceUUID = uuidFromString(args.getString(1));
                characteristicUUID = uuidFromString(args.getString(2));
                byte[] data = args.getArrayBuffer(3);
                int type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                write(callbackContext, macAddress, serviceUUID, characteristicUUID, data, type);
                break;
            case START_NOTIFICATION:
                final String mac = args.getString(0);
                final UUID service = uuidFromString(args.getString(1));
                final CallbackContext cb = callbackContext;
                final UUID chars = uuidFromString(args.getString(2));
                registerNotifyCallback(cb, mac, service, chars);
                break;
            case ENABLE:
                Log.d(TAG, "We have enabled bluetooth");
                break;
            default:
                LOG.d(TAG, "Invalid action provided");
                return false;

        }
        return true;
    }

    /*
     * If an
     */
    public void connect(CallbackContext callbackContext, String macAddress) {
        Log.d(TAG, "Attempting to connect to: " + macAddress);
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
        Log.d(TAG, "device discovered and retrieved. ready to connect");
        if (device == null) {
            callbackContext.error("Could not find the peripheral:" + macAddress);
            return;
        }
        byte[] s = "a".getBytes();
        activePeripheral = new Peripheral(device, 1, s);
        if (activePeripheral != null) {
            Log.d(TAG, "connecting to peripheral");
            activePeripheral.connect(callbackContext, cordova.getActivity());
        } else {
            Log.d(TAG, "peripheral not found");
            callbackContext.error("Peripheral " + macAddress + " not found.");
        }
    }

    public void close(CallbackContext callbackContext) {
        Log.d(TAG, "Disconnecting locker");
        if (activePeripheral == null) {
            return;
        }
        activePeripheral.close(callbackContext);
    }

    public void write(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        if (activePeripheral == null) {
            callbackContext.error("Peripheral " + macAddress + " not found.");
            return;
        }

        activePeripheral.write(callbackContext, serviceUUID, characteristicUUID, data, writeType);
    }

    public void registerNotifyCallback(CallbackContext callbackContext, String macAddress, UUID serviceUUID, UUID characteristicUUID) {
        // If the peripheral doesnt exist or isnt connected we can error our
        if (activePeripheral == null) {
            Log.d(TAG, "no active peripheral");
            callbackContext.error("Unable to register for notifications because " + macAddress + " not found");
            return;
        }
        activePeripheral.registerNotifyCallback(callbackContext, serviceUUID, characteristicUUID);
    }


    public void findLowEnergyDevices(CallbackContext callbackContext, UUID[] serviceUUIDs) {
        Log.d(TAG, "findLowEnergyDevices() initiating scan" + activeState.toString());
        // this is set u so we can fire from the onLeScan
        discoverCallback = callbackContext;
        bluetoothAdapter.startLeScan(this);
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        activeState = States.SCANNING;
        Log.d(TAG, "onLeScan() device discovered");
        Peripheral peripheral = new Peripheral(device, rssi, scanRecord);
        if (discoverCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, peripheral.asJSONObject());
            result.setKeepCallback(true);
            discoverCallback.sendPluginResult(result);
        }
    }

    private UUID uuidFromString(String uuid) {
        return UUIDHelper.uuidFromString(uuid);
    }

    public UUID[] parseServiceUUIDList(JSONArray jsonArray) throws JSONException {
        List<UUID> serviceUUIDs = new ArrayList<UUID>();
        for (int i = 0; i < jsonArray.length(); i++) {
            String uuidString = jsonArray.getString(i);
            serviceUUIDs.add(uuidFromString(uuidString));
        }
        return serviceUUIDs.toArray(new UUID[jsonArray.length()]);
    }

}
