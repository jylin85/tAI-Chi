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
// Packet (notification, 18 bytes, little-endian):
//   bytes  0-11  int16 x 6  ax, ay, az in milli-g (1000 = 1 g); gx, gy, gz in tenths of deg/s (10 = 1 deg/s)
//   bytes 12-15  uint32     time the sample was taken, in this board's clock ticks (1024 per second)
//   bytes 16-17  uint16     sample number, +1 per sample, so the tablet can count lost packets
// The first 12 bytes are the same as the original 12-byte packet.
//
// Time sync: the board never adjusts its clock. The tablet compares each packet's tick time with the
// time the packet arrived, and works out how far this board's clock is from its own (ClockSync.kt).
// The ticks come from the board's 32.768 kHz crystal, the most stable clock on the board. micros() is
// not used because it switches to the CPU cycle counter (less stable, wraps every 67 s) when a
// debugger enables it.
//
// Not tested on hardware: if imu.begin() fails, check the LSM6DS3 library's I2C address (0x6A).

#include <bluefruit.h>
#include <LSM6DS3.h>
#include <Wire.h>

#define BOARD_SIDE 'R'          // 'L' for the left IMU, 'R' for the right IMU
#define SAMPLE_INTERVAL_MS 50   // 20 samples per second, on a fixed schedule

#define SERVICE_UUID "7a1c0001-5a1e-4c3b-9d00-7a1c1e5d0001"
#define DATA_UUID    "7a1c0002-5a1e-4c3b-9d00-7a1c1e5d0001"

BLEService imuService = BLEService(BLEUuid(SERVICE_UUID));
BLECharacteristic dataChar = BLECharacteristic(BLEUuid(DATA_UUID));
LSM6DS3 imu(I2C_MODE, 0x6A);

struct __attribute__((packed)) Packet {
  int16_t  v[6];    // ax, ay, az, gx, gy, gz
  uint32_t ticks;   // xTaskGetTickCount() when sampled: configTICK_RATE_HZ (1024) per second
  uint16_t seq;     // sample number
};
static_assert(sizeof(Packet) == 18, "packet must fit in one notification (20 bytes max)");
static_assert(configTICK_RATE_HZ == 1024, "the tablet assumes 1024 ticks per second");

uint32_t nextSampleMs = 0;
uint16_t seq = 0;

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
  dataChar.setFixedLen(sizeof(Packet));
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
  nextSampleMs = millis();
}

void loop() {
  // Fixed schedule: each sample is due exactly SAMPLE_INTERVAL_MS after the previous one was due,
  // so the time spent reading and sending does not add up. delay() after each sample would.
  uint32_t now = millis();
  if ((int32_t)(now - nextSampleMs) < 0) {
    delay(1);
    return;
  }
  nextSampleMs += SAMPLE_INTERVAL_MS;
  if ((int32_t)(now - nextSampleMs) >= SAMPLE_INTERVAL_MS) nextSampleMs = now;  // fell far behind: skip ahead

  if (!Bluefruit.connected()) return;

  Packet p;
  p.ticks = xTaskGetTickCount();   // stamp the moment of sampling, not of sending
  p.v[0] = pack(imu.readFloatAccelX(), 1000.0f);
  p.v[1] = pack(imu.readFloatAccelY(), 1000.0f);
  p.v[2] = pack(imu.readFloatAccelZ(), 1000.0f);
  p.v[3] = pack(imu.readFloatGyroX(), 10.0f);
  p.v[4] = pack(imu.readFloatGyroY(), 10.0f);
  p.v[5] = pack(imu.readFloatGyroZ(), 10.0f);
  p.seq = seq++;
  dataChar.notify(&p, sizeof(p));   // returns false until the tablet has enabled notifications
}
