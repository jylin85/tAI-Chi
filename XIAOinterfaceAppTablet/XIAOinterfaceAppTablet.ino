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
// Packet (notification, 20 bytes, little-endian):
//   bytes  0-11  int16 x 6  ax, ay, az in milli-g (1000 = 1 g); gx, gy, gz in tenths of deg/s (10 = 1 deg/s)
//   bytes 12-15  uint32     time the sample was taken, in this board's clock ticks (1024 per second)
//   bytes 16-17  uint16     sample number, +1 per sample, so the tablet can count lost packets
//   bytes 18-19  uint16     battery: bits 0-14 = battery voltage in millivolts (0 = not measured yet),
//                           bit 15 = 1 while the battery is charging over USB-C
// Each version only adds bytes at the end, so the tablet app reads older 12- and 18-byte packets too.
// 20 bytes is the most one notification can carry, so further data needs its own characteristic.
//
// Time sync: the board never adjusts its clock. The tablet compares each packet's tick time with the
// time the packet arrived, and works out how far this board's clock is from its own (ClockSync.kt).
// The ticks come from the board's 32.768 kHz crystal, the most stable clock on the board. micros() is
// not used because it switches to the CPU cycle counter (less stable, wraps every 67 s) when a
// debugger enables it.
//
// Battery: the XIAO measures its LiPo through an onboard divider (1 MOhm / 510 kOhm) on P0.31, which is
// only connected while P0.14 (VBAT_ENABLE) is LOW. Seeed advises keeping P0.14 LOW: if it is HIGH while
// the battery charges, P0.31 can see more than its 3.6 V limit. So it is set LOW once and left there
// (the divider then draws about 3 uA). The charge chip pulls P0.17 (~CHG) LOW while charging.
// The tablet turns the voltage into a percentage (BatteryLevel.kt).
//
// Not tested on hardware: if imu.begin() fails, check the LSM6DS3 library's I2C address (0x6A).

#include <bluefruit.h>
#include <LSM6DS3.h>
#include <Wire.h>

#define BOARD_SIDE 'R'          // 'L' for the left IMU, 'R' for the right IMU
#define SAMPLE_INTERVAL_MS 50   // 20 samples per second, on a fixed schedule
#define BATTERY_INTERVAL_MS 2000   // measure the battery every 2 s
#define PIN_CHG 23              // D23 = P0.17, the charge chip's ~CHG output (LOW while charging)

#define SERVICE_UUID "7a1c0001-5a1e-4c3b-9d00-7a1c1e5d0001"
#define DATA_UUID    "7a1c0002-5a1e-4c3b-9d00-7a1c1e5d0001"

BLEService imuService = BLEService(BLEUuid(SERVICE_UUID));
BLECharacteristic dataChar = BLECharacteristic(BLEUuid(DATA_UUID));
LSM6DS3 imu(I2C_MODE, 0x6A);

struct __attribute__((packed)) Packet {
  int16_t  v[6];    // ax, ay, az, gx, gy, gz
  uint32_t ticks;   // xTaskGetTickCount() when sampled: configTICK_RATE_HZ (1024) per second
  uint16_t seq;     // sample number
  uint16_t battery; // bits 0-14: battery mV (0 = unknown); bit 15: charging
};
static_assert(sizeof(Packet) == 20, "packet must fit in one notification (20 bytes max)");
static_assert(configTICK_RATE_HZ == 1024, "the tablet assumes 1024 ticks per second");

uint32_t nextSampleMs = 0;
uint16_t seq = 0;
uint32_t nextBatteryMs = 0;
float batteryMv = 0;            // smoothed battery voltage; 0 until the first measurement
bool charging = false;

// Battery voltage in millivolts, averaged over 8 readings.
static float readBatteryMv() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) sum += analogRead(PIN_VBAT);
  float adcMv = (sum / 8.0f) * 2400.0f / 4095.0f;    // 12-bit reading, 2.4 V full scale
  return adcMv * (1000.0f + 510.0f) / 510.0f;        // undo the 1 MOhm / 510 kOhm divider
}

static void updateBattery() {
  float mv = readBatteryMv();
  // Smooth out the dips caused by Bluetooth transmissions.
  batteryMv = (batteryMv == 0) ? mv : batteryMv * 0.8f + mv * 0.2f;
  charging = digitalRead(PIN_CHG) == LOW;
}

static int16_t pack(float v, float scale) {
  float s = v * scale;
  if (s > 32767) s = 32767;
  if (s < -32768) s = -32768;
  return (int16_t)s;
}

void setup() {
  Serial.begin(115200);

  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);      // connect the battery divider (keep LOW, see top of file)
  pinMode(PIN_CHG, INPUT_PULLUP);
  analogReference(AR_INTERNAL_2_4);    // 0-2.4 V range: a full 4.2 V battery reads 1.42 V after the divider
  analogReadResolution(12);
  updateBattery();

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

  if ((int32_t)(now - nextBatteryMs) >= 0) {
    nextBatteryMs = now + BATTERY_INTERVAL_MS;
    updateBattery();
  }

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
  uint16_t mv = (batteryMv > 32767) ? 32767 : (uint16_t)batteryMv;
  p.battery = mv | (charging ? 0x8000 : 0);
  dataChar.notify(&p, sizeof(p));   // returns false until the tablet has enabled notifications
}
