//      Internet Toybox - Performer/Audience Interaction Platform
//      "Toybox" Controller Code version 0.24 @diemastermonkey
//
//      http://pastebin.com/Y4MemCCQ
//
//  Ver 0.24
//  Support for RGB "Head Module" (bonus module)
//  RGB insert! Sending analog values to RGB pins 
//  interrupts fading, then fades to the value.
//
//  Ver 0.20
//  Java Serial port bridge working! TCP no longer part of unit.
//  Chat venue support for Moobot, NightBot (thx to JAYBOCC2!)
//  Configurable via file
//
//  Ver 0.10
//  Internal command syntax functional
//
//      b#p##TD(..)
//    b, p - static syntax tokens.
//    b#      monkeybox number
//    p##     pin number
//    T       Either "d" (digital) or "a" (analog)
//    D(...)  1 to 3 digits
//
// Examples:
//      b0p00d000   "All boxes, all pins, treat digital, set 0 (off)"
//      b1p13d1     "Box 1, pin 13, treat digital, set 1 (on/high)"
//      b1p13d001   Same as above
//      b9p16a077   "Box 9 (?!), pin 16, treat analog, set 0.77 (of what?)"
//
 
#include <Servo.h>              // Servo control only
 
// Globals
int iBlinker = 13;               // I/O pin for diag LED
int iOBLed = 13;                // Backup diag
boolean bServerActive = false;  // True when clients connected
 
// Servo control
Servo oServo;                   // Servo object rom servo.h
boolean bServoInverted = true;  // Set true if hung upside down!
boolean bServoInit = false;
int iServoPin = 8;              // Which pin servo attached
int iServoDelay = 11;           // Min delay to update servo
int iServoMove = 12;            // Target?
int iServoMin = 10;             // Min/max (%) servo position
int iServoMax = 177;
int iServoRange = iServoMax - iServoMin;      // Oft-needed so pre-calc
int iServoMid = iServoRange / 2 + iServoMin;  // To save runtime cycles
int iServoDegrees = iServoMid;        // Current pos in deg
int iServoCmd = 0;                    // Globally pending cmd from outside
int I_SERVO_SLOW = 66; int I_SERVO_NORMAL = 44; int I_SERVO_FAST = 11;
boolean bFastMove = false;            // Optional fast cam

// V0.24: Support for separate "RGB Output" object
// See RGB functions below
// To get from bridge/config: What pins are RGB unit
int iRedPin = 9;
int iGreenPin = 10;
int iBluePin = 11;
// Target values - user may change at any time
// RGB Step routine continuously moving toward them
int iRedTarget = -1;
int iBlueTarget = -1;
int iGreenTarget = -1;
// Current values - more efficient than reading them
int iRedNow = 0;
int iBlueNow = 0;
int iGreenNow = 0;
// v 0.25 user prefs for each rgb - if set, don't change
int iRedPref = -1;
int iBluePref = -1;
int iGreenPref = -1;

// A counter for RGB updates: This controls rate of color fades
// Decrement each cycle, then when zero, update RGB and recharge
// Read this as ie "fade time" in user config
int iRGBCounter = 0;
int iRGBCounterCharge = 555;    // Recharge to this - 50 is nice fade

// For user: How often to randomly change (use 0 for never)
// This is 'sides' on the dice for changes
long iRGBRandomDice = 65536^3;    // smaller = changes more often

 
// Runs once on boot
void setup() {
  Serial.begin(9600);                // Init usb
  pinMode (iBlinker, OUTPUT);  
  
  randomSeed (analogRead(A1));
  
  // Funky RGB startup display
  for (int i=0; i < 16; i++) {
    analogWrite (iRedPin + i % 3, (i % 2) * random(256));
    delay (24);
  }

  // To do: Fade to users startup color    
/*  iRedTarget = 255;
  iBlueTarget = 224;
  iGreenTarget = 0;  
*/
  fnRGBStep();      // Does all rgb maintenance
}
 
/*
 * Main
 */
void loop() {

  // V0.24: Automatic RGB "screen saver"
  // Update RGB pins if time to do so, else mb randomize targets
  if (fnRGBStep() == 0) {
    // No RGB underway, maybe randomize?
    if (random(iRGBRandomDice) == 1) {   // 1-in-x chance of changing
      if (-1 == iRedPref) { iRedTarget = random (256); }
      if (-1 == iGreenPref) { iGreenTarget = random (256); }
      if (-1 == iBluePref) { iBlueTarget = random (256); }      
    }
  }
  
  // Command processing  
  // Shunt out now if no serial input
  if (! Serial.available() ) { return; }  
  String sInput = Serial.readString();    
  int iTargetPin = fn_PinFromCommand (sInput);
  int iTargetData = fn_DataFromCommand (sInput);
  
  // Handle digital commands first (they're easy)
  if (fn_IsDigitalCommand (sInput)) {
     // "Siren commands"
    if (iTargetData == 1) {              // Digital pin going on
      digitalWrite (iTargetPin, HIGH);   // set it on
    } else {                             // Else digital pin going off
      digitalWrite (iTargetPin, LOW);    // off
    }
    return;
  }                                      // End if (is digital)
  
  // Handle commands to RGB pins first, lest they be handled as PWM
  // Just set target, fade is automatic. Not elegant but computationally cheap.
  // Could be optimized
  if (iTargetPin == iRedPin) {
    iRedTarget = iTargetData;
    iRedPref = iTargetData;
  }

  if (iTargetPin == iGreenPin) {
    iGreenTarget = iTargetData;
    iGreenPref = iTargetData;
  }

  if (iTargetPin == iBluePin) {
    iBlueTarget = iTargetData;
    iBluePref = iTargetData;
  }
 
  // Assume any remaining commands are analog PWMs i.e. servos
  int iPWMTarget = fn_PwmFromCommand (sInput);
  if (iPWMTarget > 0) {
    fn_Servo_Move_To (iTargetPin, iPWMTarget, I_SERVO_NORMAL);
  }    
}                              // End (main) loop
 
void fn_Servo_Move_To (int iArgServoPin, int iArgPos, int iArgDelayMS) {
 
  // Yes init servo regardless, pins may change in our scenario
   oServo.attach (iArgServoPin, 1100, 1200);        // 1100,1200 for eMax ES08A
 
   if (bFastMove) {                 // Optional fast move
      fn_Servo_Position(iArgServoPin, iArgPos);
      oServo.detach();
      return;                       // shunt
   }
 
   int iDir = +1;               // Assume forward
   if (iArgPos < iServoDegrees) {
    iDir = -1;
   }
 
   // Move smoothly to target position
   for (int iGoPos = iServoDegrees; iGoPos != iArgPos; iGoPos += iDir) {
    delay (iArgDelayMS);           // Else it 'runs'
    fn_Servo_Position(iArgServoPin, iGoPos);    
   }
   oServo.detach();     // maybe need it :) blame jonm_cs
}
 
void fn_Servo_Position (int iArgServoPin, int iArgPosition) {
  // Skip if invalid args
  if (iArgPosition < iServoMin || iArgPosition > iServoMax || iArgPosition > 175) {
    Serial.println ("Error: Beyond servo limits");
    return;
  } 
 
  // Actually move servo
  if (bServoInverted) {
    oServo.write (iServoMax - iArgPosition);    
  } else {
    oServo.write (iArgPosition);
  }
 
  iServoDegrees = iArgPosition;
 
  // Superfluous? https://www.arduino.cc/en/Reference/ServoDetach
  //  oServo.detach();     
 
}     // End method
 
// Return the pin specified in a cmd it BETTER be right!
int fn_PinFromCommand (String sArgCommand) {
  return (sArgCommand.substring(3,5)).toInt();  // ( ex b1p08a75 )
}    
 
// Return the data value from command (always an int)
int fn_DataFromCommand (String sArgCommand) {
  return (sArgCommand.substring(6)).toInt();  
}    
 
// Return true if command is digital, else false
boolean fn_IsDigitalCommand (String sArgCommand) {
  return (
    sArgCommand.substring(5,6).equals("d")  // Q: char compar faster?
  );
}         // end fn_IsDigitalCommand
 
// Return PWM data only from cmd (better be there!)
int fn_PwmFromCommand (String sArgCommand) {
  return (
    sArgCommand.substring (6, sArgCommand.length()) 
  ).toInt(); // WARE HARDWIRED INDEX!
}            // end fn_PinFromCommand}

// Move and set RGB values toward current global "target" values
// Also handles RGB "update" counter. Returns 1 if it was event time.
int fnRGBStep () {
  
    // Decrement counter, ditch if not time yet to update rgbs
    iRGBCounter--;          
    if (iRGBCounter > 0) {
      return (1);
    }
    
    // Ditch now if components = targets
    if (iRedNow == iRedTarget
      && iGreenNow == iGreenTarget
      && iBlueNow == iBlueTarget) {
        return (0);
    }  
   
    // If still here time it's time for RGB update - recharge counter
    iRGBCounter = iRGBCounterCharge;
    
    // Superfluous but safe as user may change things?
    pinMode (iRedPin, OUTPUT);
    pinMode (iGreenPin, OUTPUT);
    pinMode (iBluePin, OUTPUT);      
    
    iRedNow += fnToward (iRedNow, iRedTarget);  // Red, Green, Blue...
    analogWrite (iRedPin, iRedNow);
    iGreenNow += fnToward (iGreenNow, iGreenTarget);
    analogWrite (iGreenPin, iGreenNow);
    iBlueNow += fnToward (iBlueNow, iBlueTarget);
    analogWrite (iBluePin, iBlueNow);
 
   return (1);    // PS caller, not done with rgb work yet
}

// Return value that should be added to get from argFrom to argTo
int fnToward (int iArgFrom, int iArgTo) {
  if (iArgFrom < iArgTo) { return (+1); }
  if (iArgFrom > iArgTo) { return (-1); }
  return (0);      // If same
}

// RETIRED Return a random destination for the arg RGB value, or the arg value
// Die is sides set by user in iRGBRandomDice, thus == 1 allows "0 for never"
/*
int fnRGBTargetRand (int iArgNow) {
     return (random(255));
  return (iArgNow);                    // Else what you sent me
}
*/
