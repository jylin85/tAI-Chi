// haptic_transistor.ino
// tAI-Chi wristband: drive the coin vibration motor through a 2N2222 transistor.
//
// Wiring (breadboard B, slot running up/down, rows 1-17 from top,
//         a-e left of slot, f-j right of slot):
//   XIAO D0  -> jumper -> 3a
//   1 kOhm resistor 3c -> 7c
//   2N2222: C = 6f, B = 7e, E (tab leg) = 8f
//   1N4001: stripe = 2h, plain = 6h
//   Motor:  red = 2j, black = 6j
//   XIAO 3V3 -> jumper -> 2f
//   XIAO GND -> jumper -> 8j
//
// The transistor is just a switch:  D0 HIGH = motor ON,  D0 LOW = motor OFF.
// Patterns run WITHOUT delay(), so Bluetooth / clock-sync code can share loop().
//
// Serial Monitor (115200 baud) commands:
//   1 tap   2 double tap   3 form correction   4 slow down   5 speed up
//   6 good job   d demo on/off   0 stop   ? help

#ifdef ARDUINO_NRF52_ADAFRUIT
#include <Adafruit_TinyUSB.h>   // needed for Serial on the non-mbed Seeed core
#endif

const uint8_t MOTOR_PIN = D0;

// ---------- Patterns ----------
// Each pattern is a list of durations in ms: ON, OFF, ON, OFF, ... ending with 0.
const uint16_t P_TAP[]     = {80, 0};
const uint16_t P_DOUBLE[]  = {80, 120, 80, 0};
const uint16_t P_CORRECT[] = {450, 0};
const uint16_t P_SLOWER[]  = {200, 500, 200, 0};
const uint16_t P_FASTER[]  = {60, 90, 60, 90, 60, 90, 60, 0};
const uint16_t P_GOOD[]    = {40, 60, 40, 60, 40, 0};

struct Pattern { const char* name; const uint16_t* steps; };

const Pattern PATTERNS[] = {
  {"tap cue",         P_TAP},
  {"double tap",      P_DOUBLE},
  {"form correction", P_CORRECT},
  {"tempo: slow down",P_SLOWER},
  {"tempo: speed up", P_FASTER},
  {"good job",        P_GOOD},
};
const uint8_t NUM_PATTERNS = sizeof(PATTERNS) / sizeof(PATTERNS[0]);

// ---------- Motor + pattern player ----------
const uint16_t* curSteps = nullptr;  // pattern currently playing (nullptr = idle)
uint8_t  stepIdx   = 0;
uint32_t stepStart = 0;

void motorWrite(bool on) {
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
#ifdef LED_BLUE
  digitalWrite(LED_BLUE, on ? LOW : HIGH);   // onboard LED is active-LOW; mirrors motor
#endif
}

void hapticStop() {
  curSteps = nullptr;
  motorWrite(false);
}

void hapticPlay(uint8_t i) {          // i = 0 .. NUM_PATTERNS-1
  if (i >= NUM_PATTERNS) return;
  curSteps  = PATTERNS[i].steps;
  stepIdx   = 0;
  stepStart = millis();
  motorWrite(true);                   // every pattern starts with an ON step
}

bool hapticBusy() { return curSteps != nullptr; }

// Call this every pass through loop(). Never blocks.
void hapticUpdate() {
  if (!curSteps) return;
  if (millis() - stepStart < curSteps[stepIdx]) return;

  stepIdx++;
  stepStart = millis();
  if (curSteps[stepIdx] == 0) {       // end of pattern
    hapticStop();
    return;
  }
  motorWrite(stepIdx % 2 == 0);       // even steps = ON, odd steps = OFF
}

// ---------- Demo mode ----------
bool     demoOn   = false;
uint8_t  demoNext = 0;
uint32_t demoLast = 0;
const uint32_t DEMO_GAP_MS = 3000;

void printHelp() {
  Serial.println(F("\nCommands:"));
  for (uint8_t i = 0; i < NUM_PATTERNS; i++) {
    Serial.print(F("  ")); Serial.print(i + 1); Serial.print(F("  "));
    Serial.println(PATTERNS[i].name);
  }
  Serial.println(F("  d  demo on/off (cycles patterns every 3 s)"));
  Serial.println(F("  0  stop"));
  Serial.println(F("  ?  help"));
}

void setup() {
  pinMode(MOTOR_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);       // motor OFF immediately at boot
#ifdef LED_BLUE
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_BLUE, HIGH);       // LED off
#endif

  Serial.begin(115200);
  uint32_t t0 = millis();
  while (!Serial && millis() - t0 < 2000) {}   // don't hang when on battery only

  Serial.println(F("tAI-Chi haptic test (2N2222 on D0)"));
  printHelp();
  hapticPlay(0);                      // one short tap = "I'm alive"
}

void loop() {
  hapticUpdate();

  if (Serial.available()) {
    char c = Serial.read();
    if (c >= '1' && c <= '0' + NUM_PATTERNS) {
      uint8_t i = c - '1';
      demoOn = false;
      hapticPlay(i);
      Serial.print(F("Playing: ")); Serial.println(PATTERNS[i].name);
    } else if (c == '0') {
      demoOn = false;
      hapticStop();
      Serial.println(F("Stopped"));
    } else if (c == 'd' || c == 'D') {
      demoOn = !demoOn;
      demoLast = millis() - DEMO_GAP_MS;   // start right away
      Serial.println(demoOn ? F("Demo ON") : F("Demo OFF"));
      if (!demoOn) hapticStop();
    } else if (c == '?') {
      printHelp();
    }
  }

  if (demoOn && !hapticBusy() && millis() - demoLast >= DEMO_GAP_MS) {
    Serial.print(F("Demo: ")); Serial.println(PATTERNS[demoNext].name);
    hapticPlay(demoNext);
    demoNext = (demoNext + 1) % NUM_PATTERNS;
    demoLast = millis();
  }

  // Later: Bluetooth / clock-sync code goes here too, e.g.
  //   if (bleCommandReceived) hapticPlay(bleCommand);
}
