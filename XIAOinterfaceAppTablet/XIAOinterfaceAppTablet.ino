// tAI-Chi IMU: streams accelerometer + gyroscope over Bluetooth LE from a XIAO nRF52840 Sense.
//
// Flash one board with BOARD_SIDE 'L' and the other with 'R'. The tablet app tells them apart by name
// ("tAIChi-L" / "tAIChi-R").
//
// Arduino IDE setup:
//   Board:     "Seeed XIAO nRF52840 Sense" from the "Seeed nRF52 Boards" package (the non-mbed one).
//              That package has its own BLE stack (Bluefruit), so do NOT install or include ArduinoBLE.
//   Library:   "Seeed Arduino LSM6DS3" (provides LSM6DS3.h)
//
// Packet (notification, 12 bytes, little-endian int16 x 6):
//   ax, ay, az  in milli-g          (1000 = 1 g)
//   gx, gy, gz  in tenths of deg/s  (10 = 1 deg/s)
//
// Not tested on hardware: if imu.begin() fails, check the LSM6DS3 library's I2C address (0x6A).

#include <bluefruit.h>
#include <LSM6DS3.h>
#include <Wire.h>

#define BOARD_SIDE 'R'          // 'L' for the left IMU, 'R' for the right IMU
#define SEND_INTERVAL_MS 50     // 20 packets per second

#define SERVICE_UUID "7a1c0001-5a1e-4c3b-9d00-7a1c1e5d0001"
#define DATA_UUID    "7a1c0002-5a1e-4c3b-9d00-7a1c1e5d0001"

BLEService imuService = BLEService(BLEUuid(SERVICE_UUID));
BLECharacteristic dataChar = BLECharacteristic(BLEUuid(DATA_UUID));
LSM6DS3 imu(I2C_MODE, 0x6A);

static int16_t pack(float v, float scale) {
  float s = v * scale;
  if (s > 32767) s = 32767;
  if (s < -32768) s = -32768;
  return (int16_t)s;
}

void setup() {
  Serial.begin(115200);

  if (imu.begin() != 0) {
    Serial.println("IMU not found");
    while (true) delay(1000);
  }

  const char* name = (BOARD_SIDE == 'L') ? "tAIChi-L" : "tAIChi-R";

  Bluefruit.begin(1, 0);        // one peripheral connection (the tablet), no central role
  Bluefruit.setTxPower(4);
  Bluefruit.setName(name);

  imuService.begin();
  dataChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  dataChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  dataChar.setFixedLen(12);
  dataChar.begin();

  // The 128-bit service UUID goes in the advertisement; the name goes in the scan response.
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(imuService);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);   // become discoverable again if the tablet drops
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);                    // advertise forever

  Serial.print("Advertising as ");
  Serial.println(name);
}

void loop() {
  if (Bluefruit.connected()) {
    int16_t v[6] = {
      pack(imu.readFloatAccelX(), 1000.0f),
      pack(imu.readFloatAccelY(), 1000.0f),
      pack(imu.readFloatAccelZ(), 1000.0f),
      pack(imu.readFloatGyroX(), 10.0f),
      pack(imu.readFloatGyroY(), 10.0f),
      pack(imu.readFloatGyroZ(), 10.0f),
    };
    dataChar.notify(v, sizeof(v));   // returns false until the tablet has enabled notifications
  }
  delay(SEND_INTERVAL_MS);
}
