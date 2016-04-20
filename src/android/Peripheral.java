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

import android.app.Activity;

import android.bluetooth.*;
import android.content.Context;
import android.util.Base64;
import android.util.Log;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Peripheral extends BluetoothGattCallback {

    public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUIDHelper.uuidFromString("2902");
    private static final String TAG = "Peripheral";

    private BluetoothDevice device;
    private Activity activity;
    private byte[] advertisingData;
    private boolean expectDisconnect = false;
    private byte reconnectAttempts = 0;
    private int advertisingRSSI;
    private boolean connected = false;
    private boolean servicesDiscovered = false;
    private boolean processing = false;

    BluetoothGatt gatt;

    private CallbackContext commandContext;
    private CallbackContext notifyContext;

    public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord) {
        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;

    }

    // COMMANDS

    public void connect(CallbackContext callbackContext, Activity activity) {
        Log.d(TAG, "Attempting to establish new connection to locker: " + reconnectAttempts);
        commandContext = callbackContext;
        expectDisconnect = false;
        processing = true;
        this.activity = activity;
        BluetoothDevice device = this.device;
        gatt = device.connectGatt(activity, false, this);
    }

    public void close(CallbackContext callbackContext) {
        Log.d(TAG, "Attempting to disconnect from a locker.");
        commandContext = callbackContext;
        expectDisconnect = true;
        processing = true;
        // should we be checking that gatt isn't null here? Feels like that should never be the case
        // and if it is there is a logic issue which needs to be fixed.
        if (gatt == null) {
            Log.d(TAG, "GATT is null, we are already disconnected");
            commandContext.success();
            return;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (!bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).contains(this.device)) {
            Log.d(TAG, "I think we are not connected");
        }

        gatt.disconnect();
    }

    /*
     * This callback is triggered only once when we are trying to establish a connection to the locker.
     *
     */
    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        Log.d(TAG, "Attempting to discover locker services"+ status);

        // If we have not been able to discover services what should we do?
        if (status != BluetoothGatt.GATT_SUCCESS) {
//            close(commandContext);
            return;
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, this.asJSONObject(gatt));
        Log.d(TAG, gatt.getServices().toString());
        commandContext.sendPluginResult(result);
        servicesDiscovered = true;
        processing = false;
    }

    /*
     * We don't actually need to do anything here?
     */
    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);
        Log.d(TAG, "Descriptor write: " +status);
        if (status == gatt.GATT_SUCCESS) {
            if (notifyContext != null) {
                notifyContext.success();
            }
            // check if there is an outstanding write command
        } else {
            if (status == 133) {
                commandContext = notifyContext;
                expectDisconnect = false;
            }
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        Log.d(TAG, "onConnectionStateChange: " + status + " : " + newState);

        switch (newState) {
            case BluetoothProfile.STATE_CONNECTING:
                Log.d(TAG, "CURRENTLY CONNECTING");
                return;
            case BluetoothProfile.STATE_CONNECTED:
                Log.d(TAG, "SUCCESSFULLY CONNECTED");
                // an error occurred while attempting to discover services. this is NOT a connection
                // error
                if (connected) {
                    Log.d(TAG, "You are already connected, nothing to do...");
                    return;
                }
                if (!gatt.discoverServices()) {
                    Log.d(TAG, "Error discovering services of CONNECTED peripheral.");
                    close(commandContext);
                }
                return;
            case BluetoothProfile.STATE_DISCONNECTING:
                Log.d(TAG, "CURRENTLY DISCONNECTING");
                return;
            case BluetoothProfile.STATE_DISCONNECTED:
                Log.d(TAG, "SUCCESSFULLY DISCONNECTED");
                // If we actually issued a disconnect from the door, this is a success, otherwise
                // we can try to reconnect
                gatt.close();
                new java.util.Timer().schedule(
                        new java.util.TimerTask() {
                            @Override
                            public void run() {
                                connected = false;
                                if (expectDisconnect) {
                                    commandContext.success("You have been disconnected from the door: ");
                                } else {
                                    commandContext.error("You were unexpectedly disconnected from the door: ");
                                }
                            }
                        },
                        4000
                );
                return;
            default:
                commandContext.error("An unexpected response was returned from the new locker connection state");
                Log.d(TAG, "UNEXPECTED STATE" + newState);
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            // do we need a unique error handler to handle unexected error without mashing it
            // together with connect/disconnect callback handlers?
            Log.d(TAG, "An unexpected connection error has occured");
            return;
        }

    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        Log.d(TAG, "onCharacteristicChanged " + characteristic);
        if (commandContext != null) {
            Log.d(TAG, "We've received a notification for something");
            PluginResult pr = new PluginResult(PluginResult.Status.OK, characteristic.getValue());
            pr.setKeepCallback(true);
            commandContext.sendPluginResult(pr);
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);
        Log.d(TAG, "onCharacteristicWrite");
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "ERROR WRITING");
            commandContext.error(status);
        }
    }

    public void write(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        commandContext = callbackContext;
        expectDisconnect = false;
        if (gatt == null) {
            Log.d(TAG, "gatt is null??");
            return;
        }

        // If we were disconnected in the meantime
        if (!connected && !servicesDiscovered) {
            Log.d(TAG, "You have been disconnected");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findWritableCharacteristic(service, characteristicUUID, writeType);

        if (characteristic == null) {
            Log.d(TAG, "characteristics are null");
            return;
        }
        characteristic.setValue(data);
        characteristic.setWriteType(writeType);

        if (!gatt.writeCharacteristic(characteristic)) {
            Log.d(TAG, "Unable to initialize write. This does not mean the write has failed");
        }
    }

    // This seems way too complicated
    public void registerNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        if (gatt == null) {
            Log.d(TAG, "BluetoothGatt is null");
            return;
        }

        expectDisconnect = false;
        notifyContext = callbackContext;
        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);

        if (characteristic == null) {
            Log.d(TAG, "Characteristic " + characteristicUUID + " not found");
            return;
        }


        // if we were unable to register for notifications
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.d(TAG, "Failed to register notification for ");
            return;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
        if (!descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            Log.d(TAG, "unable to set descriptor value");
            return;
        }

        if (!gatt.writeDescriptor(descriptor)) {
            Log.d(TAG, "unable to initiate write descriptor");
            return;
        }
    }

    // HANDLING THE COMMAND QUEUE

    private void next() {
        // check if we are waiting for a command to finish

        // if there are no further commands queued, we can exit

    }















































    // Some devices reuse UUIDs across characteristics, so we can't use service.getCharacteristic(characteristicUUID)
    // instead check the UUID and properties for each characteristic in the service until we find the best match
    // This function prefers Notify over Indicate
    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;
        // Check for Notify first
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }
        return characteristic;
    }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service, UUID characteristicUUID, int writeType) {
        BluetoothGattCharacteristic characteristic = null;

        // get write property
        int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        }

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & writeProperty) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    // --------------------------------------------------------------------------------------------
    // JSON STUFF WE DONT CARE ABOUT YET
    // --------------------------------------------------------------------------------------------

    public JSONObject asJSONObject()  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("advertising", byteArrayToJSON(advertisingData));
            // TODO real RSSI if we have it, else
            json.put("rssi", advertisingRSSI);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(BluetoothGatt gatt) {

        JSONObject json = asJSONObject();

        try {
            JSONArray servicesArray = new JSONArray();
            JSONArray characteristicsArray = new JSONArray();
            json.put("services", servicesArray);
            json.put("characteristics", characteristicsArray);

            if (gatt != null) {
                for (BluetoothGattService service : gatt.getServices()) {
                    servicesArray.put(UUIDHelper.uuidToString(service.getUuid()));

                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        JSONObject characteristicsJSON = new JSONObject();
                        characteristicsArray.put(characteristicsJSON);

                        characteristicsJSON.put("service", UUIDHelper.uuidToString(service.getUuid()));
                        characteristicsJSON.put("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));
                        //characteristicsJSON.put("instanceId", characteristic.getInstanceId());

                        characteristicsJSON.put("properties", Helper.decodeProperties(characteristic));
                        // characteristicsJSON.put("propertiesValue", characteristic.getProperties());

                        if (characteristic.getPermissions() > 0) {
                            characteristicsJSON.put("permissions", Helper.decodePermissions(characteristic));
                            // characteristicsJSON.put("permissionsValue", characteristic.getPermissions());
                        }

                        JSONArray descriptorsArray = new JSONArray();

                        for (BluetoothGattDescriptor descriptor: characteristic.getDescriptors()) {
                            JSONObject descriptorJSON = new JSONObject();
                            descriptorJSON.put("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
                            descriptorJSON.put("value", descriptor.getValue()); // always blank

                            if (descriptor.getPermissions() > 0) {
                                descriptorJSON.put("permissions", Helper.decodePermissions(descriptor));
                                // descriptorJSON.put("permissionsValue", descriptor.getPermissions());
                            }
                            descriptorsArray.put(descriptorJSON);
                        }
                        if (descriptorsArray.length() > 0) {
                            characteristicsJSON.put("descriptors", descriptorsArray);
                        }
                    }
                }
            }
        } catch (JSONException e) { // TODO better error handling
            e.printStackTrace();
        }

        return json;
    }

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return object;
    }

}
