package com.megster.cordova.ble.central;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.util.Log;

import org.apache.cordova.CallbackContext;

public class Command extends BluetoothGattCallback {

    Activity activity;
    BluetoothDevice device;
    BluetoothGatt gatt;
    CallbackContext callbackContext;

    public int type;

    // Connect command context
    public Command(BluetoothDevice device, CallbackContext callbackContext, Activity activity) {
        this.device = device;
        this.callbackContext = callbackContext;
        this.activity = activity;
    }

    public void exec() {
        Log.d("ConnectCommand", "Attempting to establish new connection to locker: ");
        gatt = device.connectGatt(activity, false, this);
    }

    /*
     * This callback is triggered only once when we are trying to establish a connection to the locker.
     *
     */
    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        Log.d("ConnectCommand", "Attempting to discover locker services");
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        Log.d("ConnectCommand", "onConnectionStateChange" + status + " : " + newState);
    }
}

//class BLECommand {
//    // Types
//    public static int READ = 10000;
//    public static int REGISTER_NOTIFY = 10001;
//    public static int REMOVE_NOTIFY = 10002;
//    // BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
//    // BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
//
//    private CallbackContext callbackContext;
//    private UUID serviceUUID;
//    private UUID characteristicUUID;
//    private byte[] data;
//    private int type;
//
//
//    public BLECommand(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, int type) {
//        this.callbackContext = callbackContext;
//        this.serviceUUID = serviceUUID;
//        this.characteristicUUID = characteristicUUID;
//        this.type = type;
//    }
//
//    public BLECommand(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int type) {
//        this.callbackContext = callbackContext;
//        this.serviceUUID = serviceUUID;
//        this.characteristicUUID = characteristicUUID;
//        this.data = data;
//        this.type = type;
//    }
//
//    public int getType() {
//        return type;
//    }
//
//    public CallbackContext getCallbackContext() {
//        return callbackContext;
//    }
//
//    public UUID getServiceUUID() {
//        return serviceUUID;
//    }
//
//    public UUID getCharacteristicUUID() {
//        return characteristicUUID;
//    }
//
//    public byte[] getData() {
//        return data;
//    }
//}