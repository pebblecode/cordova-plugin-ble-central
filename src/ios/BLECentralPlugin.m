//
//  BLECentralPlugin.m
//  BLE Central Cordova Plugin
//
//  (c) 2104-2015 Don Coleman
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

#import "BLECentralPlugin.h"
#import <Cordova/CDV.h>

@interface BLECentralPlugin()
- (void)stopScanTimer:(NSTimer *)timer;
@end

@implementation BLECentralPlugin

@synthesize manager;
@synthesize activePeripheral;

- (void)pluginInitialize {

    NSLog(@"Cordova BLE Central Plugin");
    NSLog(@"(c)2014-2015 Don Coleman");

    [super pluginInitialize];

    expectDisconnect = false;
    
    activePeripheral = nil;
    manager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];

    commandCallbackId = nil;
    servicesToDiscoverCharacteristicsFor = nil;
}

#pragma mark - Cordova Plugin Methods

- (void)connect:(CDVInvokedUrlCommand *)command {

    NSLog(@"Connect");
    
    commandCallbackId = [command.callbackId copy];
    
    NSString *uuidString = [command.arguments objectAtIndex:0];
    
    NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:uuidString];
    NSArray *uuidArray = @[uuid];
    NSArray *peripheralArray = [manager retrievePeripheralsWithIdentifiers:uuidArray];

    if ([peripheralArray count] < 1) {
        NSString *error = [NSString stringWithFormat:@"Could not find peripheral %@.", uuid];
        NSLog(@"Connect error: %@", uuidString);
        CDVPluginResult *pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    
    activePeripheral = [peripheralArray firstObject];
    
    if (activePeripheral) {
        NSLog(@"Connecting to peripheral with UUID : %@", uuid);
        expectDisconnect = false;
        [manager connectPeripheral:activePeripheral options:nil];
    }

}

// disconnect: function (device_id, success, failure) {
- (void)disconnect:(CDVInvokedUrlCommand*)command {
    NSLog(@"Disconnect");

    commandCallbackId = [command.callbackId copy];
    
    if (activePeripheral == nil || activePeripheral == CBPeripheralStateDisconnected) {
        NSLog(@"No active peripheral or already disconnected");
        // Just return OK
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    
    expectDisconnect = true;
    [manager cancelPeripheralConnection:activePeripheral];
    
}

// write: function (device_id, service_uuid, characteristic_uuid, value, success, failure) {
- (void)write:(CDVInvokedUrlCommand*)command {

    commandCallbackId = [command.callbackId copy];
    
    BLECommandContext *context = [self getData:command prop:CBCharacteristicPropertyWrite];
    NSData *message = [command.arguments objectAtIndex:3]; // This is binary
    if (context) {

        if (message == nil) {
            CDVPluginResult *pluginResult = nil;
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"message was null"];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            return;
        }
        
        CBPeripheral *peripheral = [context peripheral];
        CBCharacteristic *characteristic = [context characteristic];

        // TODO need to check the max length
        [peripheral writeValue:message forCharacteristic:characteristic type:CBCharacteristicWriteWithResponse];

        // response is sent from didWriteValueForCharacteristic
    }

}

// success callback is called on notification
// notify: function (device_id, service_uuid, characteristic_uuid, success, failure) {
- (void)startNotification:(CDVInvokedUrlCommand*)command {
    NSLog(@"Registering for notification");

    commandCallbackId = [command.callbackId copy];
    
    BLECommandContext *context = [self getData:command prop:CBCharacteristicPropertyNotify]; // TODO name this better

    if (context) {
        CBPeripheral *peripheral = [context peripheral];
        CBCharacteristic *characteristic = [context characteristic];

        [peripheral setNotifyValue:YES forCharacteristic:characteristic];
    }

}

- (void)isEnabled:(CDVInvokedUrlCommand*)command {

    CDVPluginResult *pluginResult = nil;
    int bluetoothState = [manager state];

    BOOL enabled = bluetoothState == CBCentralManagerStatePoweredOn;

    if (enabled) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsInt:bluetoothState];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)scan:(CDVInvokedUrlCommand*)command {

    NSLog(@"scan");
    discoverPeripherialCallbackId = [command.callbackId copy];

    NSArray *serviceUUIDStrings = [command.arguments objectAtIndex:0];
    NSNumber *timeoutSeconds = [command.arguments objectAtIndex:1];
    NSMutableArray *serviceUUIDs = [NSMutableArray new];

    for (int i = 0; i < [serviceUUIDStrings count]; i++) {
        CBUUID *serviceUUID =[CBUUID UUIDWithString:[serviceUUIDStrings objectAtIndex: i]];
        [serviceUUIDs addObject:serviceUUID];
    }

    [manager scanForPeripheralsWithServices:serviceUUIDs options:nil];

    [NSTimer scheduledTimerWithTimeInterval:[timeoutSeconds floatValue]
                                     target:self
                                   selector:@selector(stopScanTimer:)
                                   userInfo:[command.callbackId copy]
                                    repeats:NO];

}

- (void)startScan:(CDVInvokedUrlCommand*)command {

    NSLog(@"startScan");
    discoverPeripherialCallbackId = [command.callbackId copy];
    NSArray *serviceUUIDStrings = [command.arguments objectAtIndex:0];
    NSMutableArray *serviceUUIDs = [NSMutableArray new];

    for (int i = 0; i < [serviceUUIDStrings count]; i++) {
        CBUUID *serviceUUID =[CBUUID UUIDWithString:[serviceUUIDStrings objectAtIndex: i]];
        [serviceUUIDs addObject:serviceUUID];
    }

    [manager scanForPeripheralsWithServices:serviceUUIDs options:nil];

}

- (void)stopScan:(CDVInvokedUrlCommand*)command {

    NSLog(@"stopScan");

    [manager stopScan];

    if (discoverPeripherialCallbackId) {
        discoverPeripherialCallbackId = nil;
    }

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];

}


- (void)isConnected:(CDVInvokedUrlCommand*)command {

    CDVPluginResult *pluginResult = nil;
    
    if (activePeripheral && activePeripheral.state == CBPeripheralStateConnected) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not connected"];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

#pragma mark - timers

-(void)stopScanTimer:(NSTimer *)timer {
    NSLog(@"stopScanTimer");

    [manager stopScan];

    if (discoverPeripherialCallbackId) {
        discoverPeripherialCallbackId = nil;
    }
}

#pragma mark - CBCentralManagerDelegate

- (void)centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral advertisementData:(NSDictionary *)advertisementData RSSI:(NSNumber *)RSSI {

    [peripheral setAdvertisementData:advertisementData RSSI:RSSI];

    if (discoverPeripherialCallbackId) {
        CDVPluginResult *pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[peripheral asDictionary]];
        NSLog(@"Discovered %@", [peripheral asDictionary]);
        [pluginResult setKeepCallbackAsBool:TRUE];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:discoverPeripherialCallbackId];
    }

}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central
{
    NSLog(@"Status of CoreBluetooth central manager changed %ld %@", (long)central.state, [self centralManagerStateToString: central.state]);

    if (central.state == CBCentralManagerStateUnsupported)
    {
        NSLog(@"=============================================================");
        NSLog(@"WARNING: This hardware does not support Bluetooth Low Energy.");
        NSLog(@"=============================================================");
    }
}

- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral {

    NSLog(@"didConnectPeripheral");

    peripheral.delegate = self;

    // NOTE: it's inefficient to discover all services
    [peripheral discoverServices:nil];

    // NOTE: not calling connect success until characteristics are discovered
}

- (void)centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {

    NSLog(@"didDisconnectPeripheral %@", error);

    if (commandCallbackId) {
        
        CDVPluginResult *pluginResult = nil;
        
        if (expectDisconnect) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        } else {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:[peripheral asDictionary]];
        }
        
        
        [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
    }

}

- (void)centralManager:(CBCentralManager *)central didFailToConnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {

    NSLog(@"didFailToConnectPeripheral");

    if (commandCallbackId) {
    
        CDVPluginResult *pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:    [peripheral asDictionary]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
        
    }

}

#pragma mark CBPeripheralDelegate

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {

    NSLog(@"didDiscoverServices");

    // save the services to tell when all characteristics have been discovered
    servicesToDiscoverCharacteristicsFor = [NSMutableSet new];
    [servicesToDiscoverCharacteristicsFor addObjectsFromArray:peripheral.services];

    for (CBService *service in peripheral.services) {
        [peripheral discoverCharacteristics:nil forService:service]; // discover all is slow
    }
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverCharacteristicsForService:(CBService *)service error:(NSError *)error {

    NSLog(@"didDiscoverCharacteristicsForService");

    [servicesToDiscoverCharacteristicsFor removeObject:service];

    if ([servicesToDiscoverCharacteristicsFor count] == 0) {
        // Characteristics now discovered for all services - call success callback
        if (commandCallbackId) {
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:[peripheral asDictionary]];
            [pluginResult setKeepCallbackAsBool:TRUE];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
        }
    }

    NSLog(@"Found characteristics for service %@", service);
    for (CBCharacteristic *characteristic in service.characteristics) {
        NSLog(@"Characteristic %@", characteristic);
    }

}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
    NSLog(@"didUpdateValueForCharacteristic");

    if (commandCallbackId) {
        NSData *data = characteristic.value; // send RAW data to Javascript

        CDVPluginResult *pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArrayBuffer:data];
        [pluginResult setKeepCallbackAsBool:TRUE]; // keep for notification
        [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
    }

}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {

    CDVPluginResult *pluginResult = nil;

    if (commandCallbackId) {

        if (error) {
            NSLog(@"Error: %@", error);
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
        } else {
            NSLog(@"Registered for notifications");
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
        }
        
    }

}


- (void)peripheral:(CBPeripheral *)peripheral didWriteValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
    
    if (commandCallbackId) {
        CDVPluginResult *pluginResult = nil;
        if (error) {
            NSLog(@"Error: %@", error);
            pluginResult = [CDVPluginResult
                resultWithStatus:CDVCommandStatus_ERROR
                messageAsString:[error localizedDescription]
            ];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:commandCallbackId];
        }
    }

}

#pragma mark - internal implemetation

// RedBearLab
-(CBService *) findServiceFromUUID:(CBUUID *)UUID p:(CBPeripheral *)p
{
    for(int i = 0; i < p.services.count; i++)
    {
        CBService *s = [p.services objectAtIndex:i];
        if ([self compareCBUUID:s.UUID UUID2:UUID])
            return s;
    }

    return nil; //Service not found on this peripheral
}

// Find a characteristic in service with a specific property
-(CBCharacteristic *) findCharacteristicFromUUID:(CBUUID *)UUID service:(CBService*)service prop:(CBCharacteristicProperties)prop
{
    NSLog(@"Looking for %@ with properties %lu", UUID, (unsigned long)prop);
    for(int i=0; i < service.characteristics.count; i++)
    {
        CBCharacteristic *c = [service.characteristics objectAtIndex:i];
        if ((c.properties & prop) != 0x0 && [c.UUID.UUIDString isEqualToString: UUID.UUIDString]) {
            return c;
        }
    }
   return nil; //Characteristic with prop not found on this service
}

// Find a characteristic in service by UUID
-(CBCharacteristic *) findCharacteristicFromUUID:(CBUUID *)UUID service:(CBService*)service
{
    NSLog(@"Looking for %@", UUID);
    for(int i=0; i < service.characteristics.count; i++)
    {
        CBCharacteristic *c = [service.characteristics objectAtIndex:i];
        if ([c.UUID.UUIDString isEqualToString: UUID.UUIDString]) {
            return c;
        }
    }
   return nil; //Characteristic not found on this service
}

// RedBearLab
-(int) compareCBUUID:(CBUUID *) UUID1 UUID2:(CBUUID *)UUID2
{
    char b1[16];
    char b2[16];
    [UUID1.data getBytes:b1];
    [UUID2.data getBytes:b2];

    if (memcmp(b1, b2, UUID1.data.length) == 0)
        return 1;
    else
        return 0;
}

// expecting deviceUUID, serviceUUID, characteristicUUID in command.arguments
-(BLECommandContext*) getData:(CDVInvokedUrlCommand*)command prop:(CBCharacteristicProperties)prop {
    NSLog(@"getData");

    CDVPluginResult *pluginResult = nil;

    NSString *deviceUUIDString = [command.arguments objectAtIndex:0];
    NSString *serviceUUIDString = [command.arguments objectAtIndex:1];
    NSString *characteristicUUIDString = [command.arguments objectAtIndex:2];

    CBUUID *serviceUUID = [CBUUID UUIDWithString:serviceUUIDString];
    CBUUID *characteristicUUID = [CBUUID UUIDWithString:characteristicUUIDString];

    if (!activePeripheral) {

        NSLog(@"No active peripheral %@", deviceUUIDString);

        NSString *errorMessage = [NSString stringWithFormat:@"Could not find peripherial with UUID %@", deviceUUIDString];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:errorMessage];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];

        return nil;
    }

    CBService *service = [self findServiceFromUUID:serviceUUID p:activePeripheral];

    if (!service)
    {
        NSLog(@"Could not find service with UUID %@ on peripheral with UUID %@",
              serviceUUIDString,
              activePeripheral.identifier.UUIDString);


        NSString *errorMessage = [NSString stringWithFormat:@"Could not find service with UUID %@ on peripheral with UUID %@",
                                  serviceUUIDString,
                                  activePeripheral.identifier.UUIDString];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:errorMessage];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];

        return nil;
    }

    CBCharacteristic *characteristic = [self findCharacteristicFromUUID:characteristicUUID service:service prop:prop];

    // Special handling for INDICATE. If charateristic with notify is not found, check for indicate.
    if (prop == CBCharacteristicPropertyNotify && !characteristic) {
        characteristic = [self findCharacteristicFromUUID:characteristicUUID service:service prop:CBCharacteristicPropertyIndicate];
    }

    // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
    if (!characteristic) {
        characteristic = [self findCharacteristicFromUUID:characteristicUUID service:service];
    }

    if (!characteristic)
    {
        NSLog(@"Could not find characteristic with UUID %@ on service with UUID %@ on peripheral with UUID %@",
              characteristicUUIDString,
              serviceUUIDString,
              activePeripheral.identifier.UUIDString);

        NSString *errorMessage = [NSString stringWithFormat:
                                  @"Could not find characteristic with UUID %@ on service with UUID %@ on peripheral with UUID %@",
                                  characteristicUUIDString,
                                  serviceUUIDString,
                                  activePeripheral.identifier.UUIDString];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:errorMessage];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];

        return nil;
    }

    BLECommandContext *context = [[BLECommandContext alloc] init];
    [context setPeripheral:activePeripheral];
    [context setService:service];
    [context setCharacteristic:characteristic];
    return context;

}

-(NSString *) keyForPeripheral: (CBPeripheral *)peripheral andCharacteristic:(CBCharacteristic *)characteristic {
    return [NSString stringWithFormat:@"%@|%@", [peripheral uuidAsString], [characteristic UUID]];
}

#pragma mark - util

- (NSString*) centralManagerStateToString: (int)state
{
    switch(state)
    {
        case CBCentralManagerStateUnknown:
            return @"State unknown (CBCentralManagerStateUnknown)";
        case CBCentralManagerStateResetting:
            return @"State resetting (CBCentralManagerStateUnknown)";
        case CBCentralManagerStateUnsupported:
            return @"State BLE unsupported (CBCentralManagerStateResetting)";
        case CBCentralManagerStateUnauthorized:
            return @"State unauthorized (CBCentralManagerStateUnauthorized)";
        case CBCentralManagerStatePoweredOff:
            return @"State BLE powered off (CBCentralManagerStatePoweredOff)";
        case CBCentralManagerStatePoweredOn:
            return @"State powered up and ready (CBCentralManagerStatePoweredOn)";
        default:
            return @"State unknown";
    }

    return @"Unknown state";
}

@end
