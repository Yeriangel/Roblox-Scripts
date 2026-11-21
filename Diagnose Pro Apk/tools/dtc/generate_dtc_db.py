#!/usr/bin/env python3
"""Generate the offline OBD2 fault-code database for Diagnose Pro.

Output: app/src/main/assets/dtc_db.json (compact UTF-8 JSON).

Run from the DiagnosePro project root:
    python3 -I tools/dtc/generate_dtc_db.py

Standard library only. Code definitions follow the generic SAE J2012 /
ISO 15031-6 wording; codes are built from component x fault-type tables.
Codes not listed here are resolved at runtime from the "groups" table.
"""

import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
OUT_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "dtc_db.json")

SEVERITIES = ("low", "medium", "high", "stop")

CODES = {}


def add(code, en, he, sev=None):
    if code in CODES:
        raise SystemExit("duplicate code definition: %s" % code)
    entry = {"en": " ".join(en.split()), "he": " ".join(he.split())}
    if sev:
        entry["sev"] = sev
    CODES[code] = entry


def cs(letter, n):
    return "%s%04d" % (letter, n)


# --------------------------------------------------------------------------
# Fault-type vocabulary: key -> (English suffix, Hebrew phrase)
# --------------------------------------------------------------------------
FT = {
    "base": ("", "תקלה"),
    "c": ("Circuit", "תקלה במעגל"),
    "o": ("Circuit/Open", "תקלה במעגל / מעגל פתוח"),
    "rp": ("Circuit Range/Performance", "בעיית טווח/ביצועים"),
    "l": ("Circuit Low", "מתח נמוך במעגל"),
    "h": ("Circuit High", "מתח גבוה במעגל"),
    "i": ("Circuit Intermittent", "תקלה לסירוגין במעגל"),
    "ie": ("Circuit Intermittent/Erratic", "אות לסירוגין / לא יציב"),
    "co": ("Control Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"),
    "cc": ("Control Circuit", "תקלה במעגל הבקרה"),
    "crp": ("Control Circuit Range/Performance", "בעיית טווח/ביצועים במעגל הבקרה"),
    "cl": ("Control Circuit Low", "מתח נמוך במעגל הבקרה"),
    "ch": ("Control Circuit High", "מתח גבוה במעגל הבקרה"),
    "rp2": ("Range/Performance", "בעיית טווח/ביצועים"),
    "lo": ("Low", "ערך נמוך"),
    "hi": ("High", "ערך גבוה"),
    "perf": ("Performance", "בעיית ביצועים"),
    "pso": ("Performance or Stuck Off", "בעיית ביצועים או תקוע במצב כבוי"),
    "son": ("Stuck On", "תקוע במצב מופעל"),
    "el": ("Electrical", "תקלה חשמלית"),
    "int": ("Intermittent", "תקלה לסירוגין"),
    "ns": ("Circuit No Signal", "אין אות"),
    "nsig": ("No Signal", "אין אות"),
    "open": ("Circuit Open", "מעגל פתוח"),
    "short": ("Circuit Shorted", "קצר במעגל"),
    "lv": ("Circuit Low Voltage", "מתח נמוך במעגל"),
    "hv": ("Circuit High Voltage", "מתח גבוה במעגל"),
    "li": ("Circuit Low Input", "אות כניסה נמוך"),
    "hin": ("Circuit High Input", "אות כניסה גבוה"),
    "sc": ("Sense Circuit/Open", "תקלה במעגל החישה / מעגל פתוח"),
    "src": ("Sense Circuit Range/Performance", "בעיית טווח/ביצועים במעגל החישה"),
    "sl": ("Sense Circuit Low", "מתח נמוך במעגל החישה"),
    "sh": ("Sense Circuit High", "מתח גבוה במעגל החישה"),
    "si": ("Sense Circuit Intermittent", "תקלה לסירוגין במעגל החישה"),
}

S5 = ["c", "rp", "l", "h", "i"]          # classic sensor block
S5E = ["c", "rp", "l", "h", "ie"]        # sensor block, intermittent/erratic
S4 = ["c", "rp", "l", "h"]
CO3 = ["co", "cl", "ch"]
CO4 = ["co", "crp", "cl", "ch"]
SOL5 = ["base", "pso", "son", "el", "int"]   # transmission solenoid block
SPD4 = ["c", "rp", "ns", "i"]                # speed sensor block


def _fmt(tpl, suffix, sep):
    if "{}" in tpl:
        return tpl.replace("{}", suffix)
    if not suffix:
        return tpl
    return tpl + sep + suffix


def series(start, en, he, fts, sev=None):
    """Consecutive codes starting at `start` (decimal numbering).

    en / he are templates; "{}" marks where the fault-type phrase goes,
    otherwise it is appended (English: space, Hebrew: dash)."""
    letter, n = start[0], int(start[1:])
    for i, ft in enumerate(fts):
        if ft is None:
            continue
        s = sev
        if isinstance(ft, tuple):
            fe, fh = ft[0], ft[1]
            if len(ft) > 2:
                s = ft[2]
        else:
            fe, fh = FT[ft]
        add(cs(letter, n + i), _fmt(en, fe, " "), _fmt(he, fh, " – "), s)


def singles(rows, sev=None):
    for row in rows:
        code, en, he = row[0], row[1], row[2]
        add(code, en, he, row[3] if len(row) > 3 else sev)


def bs_en(b, s):
    return "(Bank %d Sensor %d)" % (b, s)


def bs_he(b, s):
    return "(בנק %d חיישן %d)" % (b, s)


LETTERS = "ABCDEFGHIJKL"

# ==========================================================================
# P00xx
# ==========================================================================
series("P0001", "Fuel Volume Regulator Control", "וסת נפח הדלק", ["o", "rp", "l", "h"], "medium")
series("P0005", 'Fuel Shutoff Valve "A" Control', "שסתום ניתוק דלק A", ["o", "l", "h"], "medium")
singles([
    ("P0008", "Engine Position System Performance (Bank 1)", "בעיית ביצועים במערכת מיקום המנוע – תזמון גל ארכובה/גל זיזים (בנק 1)", "medium"),
    ("P0009", "Engine Position System Performance (Bank 2)", "בעיית ביצועים במערכת מיקום המנוע – תזמון גל ארכובה/גל זיזים (בנק 2)", "medium"),
])
for bank, base in ((1, 10), (2, 20)):
    for j, (cam, cam_he) in enumerate((("A", "A (יניקה)"), ("B", "B (פליטה)"))):
        n = base + j * 3
        add(cs("P", n), '"%s" Camshaft Position Actuator Circuit/Open (Bank %d)' % (cam, bank),
            "מפעיל תזמון גל זיזים %s (VVT) – תקלה במעגל / מעגל פתוח (בנק %d)" % (cam_he, bank), "medium")
        add(cs("P", n + 1), '"%s" Camshaft Position - Timing Over-Advanced or System Performance (Bank %d)' % (cam, bank),
            "תזמון גל זיזים %s מוקדם מדי או בעיית ביצועים במערכת VVT (בנק %d)" % (cam_he, bank), "medium")
        add(cs("P", n + 2), '"%s" Camshaft Position - Timing Over-Retarded (Bank %d)' % (cam, bank),
            "תזמון גל זיזים %s מאוחר מדי (בנק %d)" % (cam_he, bank), "medium")
for k, (bank, sensor) in enumerate(((1, "A"), (1, "B"), (2, "A"), (2, "B"))):
    add(cs("P", 16 + k), "Crankshaft Position - Camshaft Position Correlation (Bank %d Sensor %s)" % (bank, sensor),
        "חוסר התאמה בין גל הארכובה לגל הזיזים (בנק %d חיישן %s)" % (bank, sensor), "high")
singles([
    ("P0026", "Intake Valve Control Solenoid Circuit Range/Performance (Bank 1)", "סולנואיד בקרת שסתומי יניקה – בעיית טווח/ביצועים (בנק 1)"),
    ("P0027", "Exhaust Valve Control Solenoid Circuit Range/Performance (Bank 1)", "סולנואיד בקרת שסתומי פליטה – בעיית טווח/ביצועים (בנק 1)"),
    ("P0028", "Intake Valve Control Solenoid Circuit Range/Performance (Bank 2)", "סולנואיד בקרת שסתומי יניקה – בעיית טווח/ביצועים (בנק 2)"),
    ("P0029", "Exhaust Valve Control Solenoid Circuit Range/Performance (Bank 2)", "סולנואיד בקרת שסתומי פליטה – בעיית טווח/ביצועים (בנק 2)"),
], "medium")
# O2 sensor heater control circuits
for base, b, s in ((30, 1, 1), (36, 1, 2), (42, 1, 3), (50, 2, 1), (56, 2, 2), (62, 2, 3)):
    series(cs("P", base), "HO2S Heater Control {} " + bs_en(b, s), "גוף חימום חיישן O2 – {} " + bs_he(b, s),
           [("Circuit", "תקלה במעגל הבקרה"), ("Circuit Low", "מתח נמוך במעגל הבקרה"),
            ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
for code, b, s in (("P0053", 1, 1), ("P0054", 1, 2), ("P0055", 1, 3), ("P0059", 2, 1), ("P0060", 2, 2), ("P0061", 2, 3)):
    add(code, "HO2S Heater Resistance " + bs_en(b, s), "התנגדות גוף החימום של חיישן O2 חריגה " + bs_he(b, s), "medium")
series("P0033", 'Turbocharger/Supercharger Bypass Valve "A"', "שסתום עקיפה (Bypass) A של הטורבו/מגדש", CO3, "medium")
singles([
    ("P0039", 'Turbocharger/Supercharger Bypass Valve "A" Control Circuit Range/Performance', "שסתום עקיפה (Bypass) A של הטורבו/מגדש – בעיית טווח/ביצועים במעגל הבקרה", "medium"),
    ("P0040", "O2 Sensor Signals Swapped Bank 1 Sensor 1 / Bank 2 Sensor 1", "אותות חיישני O2 מוחלפים – בנק 1 חיישן 1 / בנק 2 חיישן 1 (חיווט הפוך)", "medium"),
    ("P0041", "O2 Sensor Signals Swapped Bank 1 Sensor 2 / Bank 2 Sensor 2", "אותות חיישני O2 מוחלפים – בנק 1 חיישן 2 / בנק 2 חיישן 2 (חיווט הפוך)", "medium"),
])
series("P0045", 'Turbocharger/Supercharger Boost Control "A"', "בקרת לחץ טורבו/מגדש A", ["o", "rp", "l", "h"], "medium")
singles([
    ("P0049", "Turbocharger/Supercharger Turbine Overspeed", "סיבובי טורבינת הטורבו גבוהים מדי", "high"),
    ("P0065", "Air Assisted Injector Control Range/Performance", "בקרת מזרקים בסיוע אוויר – בעיית טווח/ביצועים", "medium"),
    ("P0066", "Air Assisted Injector Control Circuit or Circuit Low", "בקרת מזרקים בסיוע אוויר – תקלה במעגל או מתח נמוך", "medium"),
    ("P0067", "Air Assisted Injector Control Circuit High", "בקרת מזרקים בסיוע אוויר – מתח גבוה במעגל", "medium"),
    ("P0068", "MAP/MAF - Throttle Position Correlation", "חוסר התאמה בין חיישן MAP/MAF למצב המצערת (ייתכן דליפת אוויר)", "medium"),
    ("P0069", "Manifold Absolute Pressure - Barometric Pressure Correlation", "חוסר התאמה בין לחץ הסעפת (MAP) ללחץ הברומטרי", "medium"),
])
series("P0070", 'Ambient Air Temperature Sensor "A"', "חיישן טמפרטורת אוויר חיצוני", S5, "low")
for base, kind, kind_he, b in ((75, "Intake", "יניקה", 1), (78, "Exhaust", "פליטה", 1), (81, "Intake", "יניקה", 2), (84, "Exhaust", "פליטה", 2)):
    series(cs("P", base), "%s Valve Control Solenoid {} (Bank %d)" % (kind, b),
           "סולנואיד בקרת שסתומי %s (VVT) – {} (בנק %d)" % (kind_he, b), ["c", "l", "h"], "medium")
singles([
    ("P0087", "Fuel Rail/System Pressure - Too Low", "לחץ דלק במסילה/במערכת נמוך מדי", "high"),
    ("P0088", "Fuel Rail/System Pressure - Too High", "לחץ דלק במסילה/במערכת גבוה מדי", "high"),
    ("P0089", "Fuel Pressure Regulator 1 Performance", "בעיית ביצועים בווסת לחץ הדלק 1", "high"),
])
series("P0090", "Fuel Pressure Regulator 1 Control", "וסת לחץ דלק 1", CO3, "high")
singles([
    ("P0093", "Fuel System Leak Detected - Large Leak", "זוהתה דליפת דלק גדולה במערכת", "stop"),
    ("P0094", "Fuel System Leak Detected - Small Leak", "זוהתה דליפת דלק קטנה במערכת", "high"),
])
series("P0095", "Intake Air Temperature Sensor 2", "חיישן טמפרטורת אוויר יניקה 2", S5E, "medium")

# ==========================================================================
# P01xx
# ==========================================================================
series("P0100", 'Mass or Volume Air Flow Sensor "A"', "חיישן זרימת אוויר (MAF)", S5E, "medium")
series("P0105", "Manifold Absolute Pressure/Barometric Pressure Sensor", "חיישן לחץ סעפת (MAP)/לחץ ברומטרי", S5E, "medium")
series("P0110", "Intake Air Temperature Sensor 1 {} (Bank 1)", "חיישן טמפרטורת אוויר יניקה (IAT) – {}", S5E, "medium")
series("P0115", "Engine Coolant Temperature Sensor 1", "חיישן טמפרטורת נוזל קירור (ECT)", S5E, "medium")
series("P0120", 'Throttle/Pedal Position Sensor/Switch "A"', "חיישן מצב מצערת/דוושה A (TPS)", S5, "high")
singles([
    ("P0125", "Insufficient Coolant Temperature for Closed Loop Fuel Control", "המנוע לא מתחמם מספיק למעבר לבקרת דלק בלולאה סגורה", "medium"),
    ("P0126", "Insufficient Coolant Temperature for Stable Operation", "טמפרטורת נוזל הקירור נמוכה מדי לפעולה יציבה", "medium"),
    ("P0127", "Intake Air Temperature Too High", "טמפרטורת אוויר היניקה גבוהה מדי", "medium"),
    ("P0128", "Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)", "טמפרטורת נוזל קירור נמוכה מהתרמוסטט", "medium"),
    ("P0129", "Barometric Pressure Too Low", "לחץ ברומטרי נמוך מדי", "medium"),
])
O2FT = [("Circuit", "תקלה במעגל"), ("Circuit Low Voltage", "מתח נמוך במעגל"),
        ("Circuit High Voltage", "מתח גבוה במעגל"), ("Circuit Slow Response", "תגובה איטית"),
        ("Circuit No Activity Detected", "אין פעילות של החיישן"), ("Heater Circuit", "תקלה במעגל גוף החימום")]
for base, b, s in ((130, 1, 1), (136, 1, 2), (142, 1, 3), (150, 2, 1), (156, 2, 2), (162, 2, 3)):
    series(cs("P", base), "O2 Sensor {} " + bs_en(b, s), "חיישן O2 – {} " + bs_he(b, s), O2FT, "medium")
singles([
    ("P0148", "Fuel Delivery Error", "שגיאה באספקת הדלק", "high"),
    ("P0149", "Fuel Timing Error", "שגיאה בתזמון הזרקת הדלק", "high"),
    ("P0168", "Fuel Temperature Too High", "טמפרטורת הדלק גבוהה מדי", "medium"),
    ("P0169", "Incorrect Fuel Composition", "הרכב דלק שגוי (סוג/איכות דלק)", "medium"),
    ("P0170", "Fuel Trim (Bank 1)", "תקלה בתיקון תערובת הדלק (Fuel Trim) (בנק 1)", "medium"),
    ("P0171", "System Too Lean (Bank 1)", "תערובת דלה מדי (בנק 1)", "medium"),
    ("P0172", "System Too Rich (Bank 1)", "תערובת עשירה מדי (בנק 1)", "medium"),
    ("P0173", "Fuel Trim (Bank 2)", "תקלה בתיקון תערובת הדלק (Fuel Trim) (בנק 2)", "medium"),
    ("P0174", "System Too Lean (Bank 2)", "תערובת דלה מדי (בנק 2)", "medium"),
    ("P0175", "System Too Rich (Bank 2)", "תערובת עשירה מדי (בנק 2)", "medium"),
])
series("P0176", "Fuel Composition Sensor", "חיישן הרכב הדלק", S4, "medium")
series("P0180", 'Fuel Temperature Sensor "A"', "חיישן טמפרטורת דלק A", S5, "low")
series("P0185", 'Fuel Temperature Sensor "B"', "חיישן טמפרטורת דלק B", S5, "low")
series("P0190", 'Fuel Rail Pressure Sensor "A"', "חיישן לחץ מסילת הדלק A", S5, "high")
series("P0195", "Engine Oil Temperature Sensor", "חיישן טמפרטורת שמן מנוע", S5, "medium")

# ==========================================================================
# P02xx
# ==========================================================================
add("P0200", "Injector Circuit/Open", "תקלה במעגל המזרקים / מעגל פתוח", "high")
for c in range(1, 13):
    add(cs("P", 200 + c), "Injector Circuit/Open - Cylinder %d" % c, "מזרק דלק צילינדר %d – תקלה במעגל / מעגל פתוח" % c, "high")
singles([
    ("P0213", "Cold Start Injector 1", "מזרק התנעה קרה 1 – תקלה", "medium"),
    ("P0214", "Cold Start Injector 2", "מזרק התנעה קרה 2 – תקלה", "medium"),
    ("P0215", "Engine Shutoff Solenoid", "סולנואיד דימום המנוע – תקלה", "high"),
    ("P0216", "Injector/Injection Timing Control Circuit", "תקלה במעגל בקרת תזמון ההזרקה", "high"),
    ("P0217", "Engine Coolant Over Temperature Condition", "התחממות יתר של המנוע (נוזל קירור חם מדי)", "stop"),
    ("P0218", "Transmission Fluid Over Temperature Condition", "התחממות יתר של שמן תיבת ההילוכים", "stop"),
    ("P0219", "Engine Overspeed Condition", "סיבובי מנוע גבוהים מדי (חריגת סל\"ד)", "high"),
])
series("P0220", 'Throttle/Pedal Position Sensor/Switch "B"', "חיישן מצב מצערת/דוושה B", S5, "high")
series("P0225", 'Throttle/Pedal Position Sensor/Switch "C"', "חיישן מצב מצערת/דוושה C", S5, "high")
singles([
    ("P0230", "Fuel Pump Primary Circuit", "משאבת דלק – תקלה במעגל הראשי (ממסר)", "high"),
    ("P0231", "Fuel Pump Secondary Circuit Low", "משאבת דלק – מתח נמוך במעגל המשני", "high"),
    ("P0232", "Fuel Pump Secondary Circuit High", "משאבת דלק – מתח גבוה במעגל המשני", "high"),
    ("P0233", "Fuel Pump Secondary Circuit Intermittent", "משאבת דלק – תקלה לסירוגין במעגל המשני", "high"),
    ("P0234", "Turbocharger/Supercharger Engine Overboost Condition", "לחץ טורבו/מגדש גבוה מדי (Overboost)", "high"),
])
series("P0235", 'Turbocharger/Supercharger Boost Sensor "A"', "חיישן לחץ טורבו A", S4, "medium")
series("P0239", 'Turbocharger/Supercharger Boost Sensor "B"', "חיישן לחץ טורבו B", S4, "medium")
series("P0243", 'Turbocharger/Supercharger Wastegate Solenoid "A"', "סולנואיד Wastegate A של הטורבו", ["base", "rp2", "lo", "hi"], "medium")
series("P0247", 'Turbocharger/Supercharger Wastegate Solenoid "B"', "סולנואיד Wastegate B של הטורבו", ["base", "rp2", "lo", "hi"], "medium")
_pumpft = [("Malfunction", "תקלה"), "rp2", "lo", "hi", "int"]
series("P0251", 'Injection Pump Fuel Metering Control "A" {} (Cam/Rotor/Injector)', "בקרת מינון הדלק A במשאבת ההזרקה – {}", _pumpft, "high")
series("P0256", 'Injection Pump Fuel Metering Control "B" {} (Cam/Rotor/Injector)', "בקרת מינון הדלק B במשאבת ההזרקה – {}", _pumpft, "high")
for c in range(1, 13):
    n = 261 + (c - 1) * 3
    add(cs("P", n), "Cylinder %d Injector Circuit Low" % c, "מזרק צילינדר %d – מתח נמוך במעגל" % c, "high")
    add(cs("P", n + 1), "Cylinder %d Injector Circuit High" % c, "מזרק צילינדר %d – מתח גבוה במעגל" % c, "high")
    add(cs("P", n + 2), "Cylinder %d Contribution/Balance" % c, "צילינדר %d – תרומה/איזון לא תקינים (ביצועי צילינדר חלשים)" % c, "medium")
singles([
    ("P0297", "Vehicle Overspeed Condition", "מהירות הרכב גבוהה מהמותר", "medium"),
    ("P0298", "Engine Oil Over Temperature", "טמפרטורת שמן המנוע גבוהה מדי", "stop"),
    ("P0299", "Turbocharger/Supercharger Underboost", "לחץ טורבו/מגדש נמוך מהנדרש (Underboost)", "medium"),
])

# ==========================================================================
# P03xx
# ==========================================================================
add("P0300", "Random/Multiple Cylinder Misfire Detected", "תקלות הצתה אקראיות / במספר צילינדרים", "high")
for c in range(1, 13):
    add(cs("P", 300 + c), "Cylinder %d Misfire Detected" % c, "תקלת הצתה בצילינדר %d" % c, "high")
singles([
    ("P0313", "Misfire Detected with Low Fuel", "תקלת הצתה בזמן מפלס דלק נמוך", "medium"),
    ("P0314", "Single Cylinder Misfire (Cylinder not Specified)", "תקלת הצתה בצילינדר בודד (לא צוין איזה)", "high"),
    ("P0315", "Crankshaft Position System Variation Not Learned", "לא בוצע לימוד סטיית חיישן גל הארכובה (CKP Relearn)", "low"),
    ("P0316", "Engine Misfire Detected on Startup (First 1000 Revolutions)", "תקלת הצתה בזמן ההתנעה (1000 הסיבובים הראשונים)", "high"),
    ("P0317", "Rough Road Hardware Not Present", "חומרת זיהוי דרך משובשת לא קיימת", "low"),
    ("P0318", 'Rough Road Sensor "A" Signal Circuit', "חיישן דרך משובשת A – תקלה במעגל האות", "low"),
    ("P0319", 'Rough Road Sensor "B"', "חיישן דרך משובשת B – תקלה", "low"),
])
series("P0320", "Ignition/Distributor Engine Speed Input", "אות סיבובי מנוע מההצתה/המפלג", ["c", "rp", "ns", "i"], "high")
add("P0324", "Knock Control System Error", "שגיאה במערכת בקרת הנקישות", "medium")
series("P0325", "Knock Sensor 1 {} (Bank 1 or Single Sensor)", "חיישן נקישות 1 – {} (בנק 1 או חיישן יחיד)", S5, "medium")
series("P0330", "Knock Sensor 2 {} (Bank 2)", "חיישן נקישות 2 – {} (בנק 2)", S5, "medium")
series("P0335", 'Crankshaft Position Sensor "A"', "חיישן גל ארכובה A (CKP)", S5, "high")
series("P0340", 'Camshaft Position Sensor "A" {} (Bank 1 or Single Sensor)', "חיישן גל זיזים A (CMP) – {} (בנק 1 או חיישן יחיד)", S5, "medium")
series("P0345", 'Camshaft Position Sensor "A" {} (Bank 2)', "חיישן גל זיזים A (CMP) – {} (בנק 2)", S5, "medium")
add("P0350", "Ignition Coil Primary/Secondary Circuit", "סליל הצתה – תקלה במעגל הראשוני/המשני", "high")
for k in range(12):
    add(cs("P", 351 + k), 'Ignition Coil "%s" Primary/Secondary Circuit' % LETTERS[k],
        "סליל הצתה %s (צילינדר %d) – תקלה במעגל הראשוני/המשני" % (LETTERS[k], k + 1), "high")
series("P0365", 'Camshaft Position Sensor "B" {} (Bank 1)', "חיישן גל זיזים B (CMP) – {} (בנק 1)", S5, "medium")
_tref = ["base", ("Too Many Pulses", "יותר מדי פולסים"), ("Too Few Pulses", "מעט מדי פולסים"),
         ("Intermittent/Erratic Pulses", "פולסים לסירוגין / לא יציבים"), ("No Pulses", "אין פולסים")]
series("P0370", 'Timing Reference High Resolution Signal "A"', "אות ייחוס תזמון ברזולוציה גבוהה A", _tref, "high")
series("P0375", 'Timing Reference High Resolution Signal "B"', "אות ייחוס תזמון ברזולוציה גבוהה B", _tref, "high")
singles([
    ("P0380", 'Glow Plug/Heater Circuit "A"', "מצתי להט (דיזל) – תקלה במעגל A", "medium"),
    ("P0381", "Glow Plug/Heater Indicator Circuit", "נורית מצתי להט – תקלה במעגל", "low"),
    ("P0382", 'Glow Plug/Heater Circuit "B"', "מצתי להט (דיזל) – תקלה במעגל B", "medium"),
])
series("P0385", 'Crankshaft Position Sensor "B"', "חיישן גל ארכובה B (CKP)", S5, "high")
series("P0390", 'Camshaft Position Sensor "B" {} (Bank 2)', "חיישן גל זיזים B (CMP) – {} (בנק 2)", S5, "medium")

# ==========================================================================
# P04xx – auxiliary emission controls
# ==========================================================================
singles([
    ("P0400", "Exhaust Gas Recirculation Flow", "תקלה בזרימת EGR (החזרת גזי פליטה)", "medium"),
    ("P0401", "Exhaust Gas Recirculation Flow Insufficient Detected", "זרימת EGR נמוכה מדי", "medium"),
    ("P0402", "Exhaust Gas Recirculation Flow Excessive Detected", "זרימת EGR גבוהה מדי", "medium"),
    ("P0403", "Exhaust Gas Recirculation Control Circuit", "שסתום EGR – תקלה במעגל הבקרה", "medium"),
    ("P0404", "Exhaust Gas Recirculation Control Circuit Range/Performance", "שסתום EGR – בעיית טווח/ביצועים במעגל הבקרה", "medium"),
    ("P0405", 'Exhaust Gas Recirculation Sensor "A" Circuit Low', "חיישן EGR A – מתח נמוך במעגל", "medium"),
    ("P0406", 'Exhaust Gas Recirculation Sensor "A" Circuit High', "חיישן EGR A – מתח גבוה במעגל", "medium"),
    ("P0407", 'Exhaust Gas Recirculation Sensor "B" Circuit Low', "חיישן EGR B – מתח נמוך במעגל", "medium"),
    ("P0408", 'Exhaust Gas Recirculation Sensor "B" Circuit High', "חיישן EGR B – מתח גבוה במעגל", "medium"),
    ("P0409", 'Exhaust Gas Recirculation Sensor "A" Circuit', "חיישן EGR A – תקלה במעגל", "medium"),
    ("P0410", "Secondary Air Injection System", "מערכת הזרקת אוויר משני – תקלה", "low"),
    ("P0411", "Secondary Air Injection System Incorrect Flow Detected", "מערכת הזרקת אוויר משני – זרימה לא תקינה", "low"),
    ("P0412", 'Secondary Air Injection System Switching Valve "A" Circuit', "שסתום מיתוג A של אוויר משני – תקלה במעגל", "low"),
    ("P0413", 'Secondary Air Injection System Switching Valve "A" Circuit Open', "שסתום מיתוג A של אוויר משני – מעגל פתוח", "low"),
    ("P0414", 'Secondary Air Injection System Switching Valve "A" Circuit Shorted', "שסתום מיתוג A של אוויר משני – קצר במעגל", "low"),
    ("P0415", 'Secondary Air Injection System Switching Valve "B" Circuit', "שסתום מיתוג B של אוויר משני – תקלה במעגל", "low"),
    ("P0416", 'Secondary Air Injection System Switching Valve "B" Circuit Open', "שסתום מיתוג B של אוויר משני – מעגל פתוח", "low"),
    ("P0417", 'Secondary Air Injection System Switching Valve "B" Circuit Shorted', "שסתום מיתוג B של אוויר משני – קצר במעגל", "low"),
    ("P0418", 'Secondary Air Injection System Control "A" Circuit', "ממסר/בקרת משאבת אוויר משני A – תקלה במעגל", "low"),
    ("P0419", 'Secondary Air Injection System Control "B" Circuit', "ממסר/בקרת משאבת אוויר משני B – תקלה במעגל", "low"),
])
for bank, base in ((1, 420), (2, 430)):
    b_he = "(בנק %d)" % bank
    rows = [
        ("Catalyst System Efficiency Below Threshold", "יעילות הממיר הקטליטי נמוכה מהסף", "medium"),
        ("Warm Up Catalyst Efficiency Below Threshold", "יעילות ממיר החימום (Warm-up) נמוכה מהסף", "medium"),
        ("Main Catalyst Efficiency Below Threshold", "יעילות הממיר הקטליטי הראשי נמוכה מהסף", "medium"),
        ("Heated Catalyst Efficiency Below Threshold", "יעילות הממיר המחומם נמוכה מהסף", "medium"),
        ("Heated Catalyst Temperature Below Threshold", "טמפרטורת הממיר המחומם נמוכה מהסף", "medium"),
        ("Catalyst Temperature Sensor", "חיישן טמפרטורת הממיר הקטליטי – תקלה", "low"),
        ("Catalyst Temperature Sensor Range/Performance", "חיישן טמפרטורת הממיר הקטליטי – בעיית טווח/ביצועים", "low"),
        ("Catalyst Temperature Sensor Low", "חיישן טמפרטורת הממיר הקטליטי – ערך נמוך", "low"),
        ("Catalyst Temperature Sensor High", "חיישן טמפרטורת הממיר הקטליטי – ערך גבוה", "low"),
        ("Catalyst Heater Control Circuit", "גוף חימום הממיר הקטליטי – תקלה במעגל הבקרה", "low"),
    ]
    for k, (en, he, sv) in enumerate(rows):
        add(cs("P", base + k), "%s (Bank %d)" % (en, bank), "%s %s" % (he, b_he), sv)
singles([
    ("P0440", "Evaporative Emission System", "מערכת EVAP (אדי דלק) – תקלה", "low"),
    ("P0441", "Evaporative Emission System Incorrect Purge Flow", "מערכת EVAP – זרימת ניקוז (Purge) לא תקינה", "low"),
    ("P0442", "Evaporative Emission System Leak Detected (small leak)", "מערכת EVAP – זוהתה דליפה קטנה", "low"),
    ("P0443", "Evaporative Emission System Purge Control Valve Circuit", "שסתום ניקוז (Purge) של EVAP – תקלה במעגל", "low"),
    ("P0444", "Evaporative Emission System Purge Control Valve Circuit Open", "שסתום ניקוז (Purge) של EVAP – מעגל פתוח", "low"),
    ("P0445", "Evaporative Emission System Purge Control Valve Circuit Shorted", "שסתום ניקוז (Purge) של EVAP – קצר במעגל", "low"),
    ("P0446", "Evaporative Emission System Vent Control Circuit", "שסתום אוורור (Vent) של EVAP – תקלה במעגל הבקרה", "low"),
    ("P0447", "Evaporative Emission System Vent Control Circuit Open", "שסתום אוורור (Vent) של EVAP – מעגל פתוח", "low"),
    ("P0448", "Evaporative Emission System Vent Control Circuit Shorted", "שסתום אוורור (Vent) של EVAP – קצר במעגל", "low"),
    ("P0449", "Evaporative Emission System Vent Valve/Solenoid Circuit", "שסתום/סולנואיד אוורור של EVAP – תקלה במעגל", "low"),
])
series("P0450", "Evaporative Emission System Pressure Sensor/Switch", "חיישן לחץ מערכת EVAP", S5, "low")
singles([
    ("P0455", "Evaporative Emission System Leak Detected (large leak)", "מערכת EVAP – זוהתה דליפה גדולה", "low"),
    ("P0456", "Evaporative Emission System Leak Detected (very small leak)", "מערכת EVAP – זוהתה דליפה קטנה מאוד", "low"),
    ("P0457", "Evaporative Emission System Leak Detected (fuel cap loose/off)", "מערכת EVAP – דליפה: מכסה הדלק רופף או חסר", "low"),
    ("P0458", "Evaporative Emission System Purge Control Valve Circuit Low", "שסתום ניקוז (Purge) של EVAP – מתח נמוך במעגל", "low"),
    ("P0459", "Evaporative Emission System Purge Control Valve Circuit High", "שסתום ניקוז (Purge) של EVAP – מתח גבוה במעגל", "low"),
])
series("P0460", 'Fuel Level Sensor "A"', "חיישן מפלס דלק A (מד דלק)", S5, "low")
series("P0465", "EVAP Purge Flow Sensor", "חיישן זרימת ניקוז EVAP", S5, "low")
series("P0470", 'Exhaust Pressure Sensor "A"', "חיישן לחץ פליטה A", S5, "medium")
series("P0475", 'Exhaust Pressure Control Valve "A"', "שסתום בקרת לחץ פליטה A", ["base", "rp2", "lo", "hi", "int"], "medium")
singles([
    ("P0480", "Fan 1 Control Circuit", "מאוורר קירור 1 – תקלה במעגל הבקרה", "high"),
    ("P0481", "Fan 2 Control Circuit", "מאוורר קירור 2 – תקלה במעגל הבקרה", "high"),
    ("P0482", "Fan 3 Control Circuit", "מאוורר קירור 3 – תקלה במעגל הבקרה", "high"),
    ("P0483", "Fan Rationality Check", "מאוורר קירור – בדיקת סבירות נכשלה", "high"),
    ("P0484", "Fan Circuit Over Current", "מאוורר קירור – זרם יתר במעגל", "high"),
    ("P0485", "Fan Power/Ground Circuit", "מאוורר קירור – תקלה במעגל הזנה/הארקה", "high"),
    ("P0486", 'Exhaust Gas Recirculation Sensor "B" Circuit', "חיישן EGR B – תקלה במעגל", "medium"),
    ("P0487", "Exhaust Gas Recirculation Throttle Position Control Circuit", "מצערת EGR – תקלה במעגל בקרת המיקום", "medium"),
    ("P0488", "Exhaust Gas Recirculation Throttle Position Control Range/Performance", "מצערת EGR – בעיית טווח/ביצועים בבקרת המיקום", "medium"),
    ("P0489", "Exhaust Gas Recirculation Control Circuit Low", "בקרת EGR – מתח נמוך במעגל", "medium"),
    ("P0490", "Exhaust Gas Recirculation Control Circuit High", "בקרת EGR – מתח גבוה במעגל", "medium"),
    ("P0491", "Secondary Air Injection System Insufficient Flow (Bank 1)", "מערכת אוויר משני – זרימה לא מספקת (בנק 1)", "low"),
    ("P0492", "Secondary Air Injection System Insufficient Flow (Bank 2)", "מערכת אוויר משני – זרימה לא מספקת (בנק 2)", "low"),
    ("P0493", "Fan Overspeed", "מאוורר קירור – מהירות גבוהה מדי", "medium"),
    ("P0494", "Fan Speed Low", "מאוורר קירור – מהירות נמוכה", "high"),
    ("P0495", "Fan Speed High", "מאוורר קירור – מהירות גבוהה", "medium"),
    ("P0496", "Evaporative Emission System High Purge Flow", "מערכת EVAP – זרימת ניקוז (Purge) גבוהה מדי", "low"),
    ("P0497", "Evaporative Emission System Low Purge Flow", "מערכת EVAP – זרימת ניקוז (Purge) נמוכה מדי", "low"),
    ("P0498", "Evaporative Emission System Vent Valve Control Circuit Low", "שסתום אוורור EVAP – מתח נמוך במעגל הבקרה", "low"),
    ("P0499", "Evaporative Emission System Vent Valve Control Circuit High", "שסתום אוורור EVAP – מתח גבוה במעגל הבקרה", "low"),
])

# ==========================================================================
# P05xx – speed, idle control, auxiliary inputs
# ==========================================================================
series("P0500", 'Vehicle Speed Sensor "A"', "חיישן מהירות רכב A (VSS)",
       ["base", "rp2", ("Low", "אות נמוך"), ("Intermittent/Erratic/High", "אות לסירוגין / לא יציב / גבוה")], "medium")
singles([
    ("P0504", 'Brake Switch "A"/"B" Correlation', "חוסר התאמה בין מתגי הבלם A ו-B (מתג אור בלמים)", "medium"),
    ("P0505", "Idle Air Control System", "מערכת בקרת סרק (IAC) – תקלה", "medium"),
    ("P0506", "Idle Air Control System RPM Lower Than Expected", "סיבובי סרק נמוכים מהצפוי", "medium"),
    ("P0507", "Idle Air Control System RPM Higher Than Expected", "סיבובי סרק גבוהים מהצפוי", "medium"),
    ("P0508", "Idle Air Control System Circuit Low", "בקרת סרק (IAC) – מתח נמוך במעגל", "medium"),
    ("P0509", "Idle Air Control System Circuit High", "בקרת סרק (IAC) – מתח גבוה במעגל", "medium"),
    ("P0510", "Closed Throttle Position Switch", "מתג מצערת סגורה – תקלה", "medium"),
    ("P0511", "Idle Air Control Circuit", "בקרת סרק (IAC) – תקלה במעגל", "medium"),
    ("P0512", "Starter Request Circuit", "מעגל בקשת התנעה – תקלה", "medium"),
    ("P0513", "Invalid Immobilizer Key", "מפתח אימובילייזר לא מזוהה", "high"),
    ("P0515", "Battery Temperature Sensor Circuit", "חיישן טמפרטורת מצבר – תקלה במעגל", "low"),
    ("P0516", "Battery Temperature Sensor Circuit Low", "חיישן טמפרטורת מצבר – מתח נמוך במעגל", "low"),
    ("P0517", "Battery Temperature Sensor Circuit High", "חיישן טמפרטורת מצבר – מתח גבוה במעגל", "low"),
    ("P0514", "Battery Temperature Sensor Circuit Range/Performance", "חיישן טמפרטורת מצבר – בעיית טווח/ביצועים", "low"),
    ("P0518", "Idle Air Control Circuit Intermittent", "בקרת סרק (IAC) – תקלה לסירוגין במעגל", "medium"),
    ("P0519", "Idle Air Control System Performance", "מערכת בקרת סרק – בעיית ביצועים", "medium"),
])
series("P0520", "Engine Oil Pressure Sensor/Switch", "חיישן/מתג לחץ שמן מנוע", ["c", "rp", "l", "h"], "stop")
singles([
    ("P0524", "Engine Oil Pressure Too Low", "לחץ שמן המנוע נמוך מדי", "stop"),
    ("P0525", "Cruise Control Servo Control Circuit Range/Performance", "סרבו בקרת שיוט – בעיית טווח/ביצועים במעגל הבקרה", "low"),
])
series("P0526", "Fan Speed Sensor", "חיישן מהירות מאוורר הקירור", ["c", "rp", "ns", "i"], "medium")
series("P0530", 'A/C Refrigerant Pressure Sensor "A"', "חיישן לחץ גז המזגן A", S4, "low")
add("P0534", "Air Conditioner Refrigerant Charge Loss", "אובדן גז במערכת המיזוג", "low")
series("P0535", "A/C Evaporator Temperature Sensor", "חיישן טמפרטורת מאייד המזגן", S5, "low")
series("P0540", 'Intake Air Heater "A"', "מחמם אוויר יניקה A", ["c", "l", "h"], "low")
add("P0543", 'Intake Air Heater "A" Circuit Open', "מחמם אוויר יניקה A – מעגל פתוח", "low")
series("P0544", "Exhaust Gas Temperature Sensor {} " + bs_en(1, 1), "חיישן טמפרטורת גזי פליטה (EGT) – {} " + bs_he(1, 1), ["c", "l", "h"], "medium")
series("P0547", "Exhaust Gas Temperature Sensor {} " + bs_en(2, 1), "חיישן טמפרטורת גזי פליטה (EGT) – {} " + bs_he(2, 1), ["c", "l", "h"], "medium")
series("P0550", "Power Steering Pressure Sensor/Switch", "חיישן/מתג לחץ הגה כוח", S5, "low")
series("P0555", "Brake Booster Pressure Sensor", "חיישן לחץ מגבר הבלמים", S5, "high")
singles([
    ("P0560", "System Voltage", "מתח מערכת החשמל – תקלה", "high"),
    ("P0561", "System Voltage Unstable", "מתח מערכת החשמל לא יציב", "high"),
    ("P0562", "System Voltage Low", "מתח מערכת החשמל נמוך", "high"),
    ("P0563", "System Voltage High", "מתח מערכת החשמל גבוה", "high"),
    ("P0564", 'Cruise Control Multi-Function Input "A" Circuit', "מתג רב-תכליתי של בקרת השיוט – תקלה במעגל", "low"),
    ("P0565", "Cruise Control On Signal", "בקרת שיוט – תקלה באות הפעלה (ON)", "low"),
    ("P0566", "Cruise Control Off Signal", "בקרת שיוט – תקלה באות כיבוי (OFF)", "low"),
    ("P0567", "Cruise Control Resume Signal", "בקרת שיוט – תקלה באות חידוש (RESUME)", "low"),
    ("P0568", "Cruise Control Set Signal", "בקרת שיוט – תקלה באות קביעה (SET)", "low"),
    ("P0569", "Cruise Control Coast Signal", "בקרת שיוט – תקלה באות האטה (COAST)", "low"),
    ("P0570", "Cruise Control Accelerate Signal", "בקרת שיוט – תקלה באות האצה (ACCEL)", "low"),
    ("P0571", 'Brake Switch "A" Circuit', "מתג בלם A (מתג אור בלמים) – תקלה במעגל", "medium"),
    ("P0572", 'Brake Switch "A" Circuit Low', "מתג בלם A – מתח נמוך במעגל", "medium"),
    ("P0573", 'Brake Switch "A" Circuit High', "מתג בלם A – מתח גבוה במעגל", "medium"),
    ("P0574", "Cruise Control System - Vehicle Speed Too High", "בקרת שיוט – מהירות הרכב גבוהה מדי", "low"),
    ("P0575", "Cruise Control Input Circuit", "בקרת שיוט – תקלה במעגל הכניסה", "low"),
    ("P0576", "Cruise Control Input Circuit Low", "בקרת שיוט – מתח נמוך במעגל הכניסה", "low"),
    ("P0577", "Cruise Control Input Circuit High", "בקרת שיוט – מתח גבוה במעגל הכניסה", "low"),
    ("P0578", 'Cruise Control Multi-Function Input "A" Circuit Stuck', "מתג רב-תכליתי A של בקרת השיוט – תקוע", "low"),
    ("P0579", 'Cruise Control Multi-Function Input "A" Circuit Range/Performance', "מתג רב-תכליתי A של בקרת השיוט – בעיית טווח/ביצועים", "low"),
    ("P0580", 'Cruise Control Multi-Function Input "A" Circuit Low', "מתג רב-תכליתי A של בקרת השיוט – מתח נמוך במעגל", "low"),
    ("P0581", 'Cruise Control Multi-Function Input "A" Circuit High', "מתג רב-תכליתי A של בקרת השיוט – מתח גבוה במעגל", "low"),
    ("P0582", "Cruise Control Vacuum Control Circuit/Open", "בקרת ואקום של בקרת השיוט – תקלה במעגל / מעגל פתוח", "low"),
    ("P0583", "Cruise Control Vacuum Control Circuit Low", "בקרת ואקום של בקרת השיוט – מתח נמוך במעגל", "low"),
    ("P0584", "Cruise Control Vacuum Control Circuit High", "בקרת ואקום של בקרת השיוט – מתח גבוה במעגל", "low"),
    ("P0585", 'Cruise Control Multi-Function Input "A"/"B" Correlation', "חוסר התאמה בין מתגי בקרת השיוט A ו-B", "low"),
    ("P0586", "Cruise Control Vent Control Circuit/Open", "אוורור בקרת השיוט – תקלה במעגל / מעגל פתוח", "low"),
    ("P0587", "Cruise Control Vent Control Circuit Low", "אוורור בקרת השיוט – מתח נמוך במעגל", "low"),
    ("P0588", "Cruise Control Vent Control Circuit High", "אוורור בקרת השיוט – מתח גבוה במעגל", "low"),
    ("P0589", 'Cruise Control Multi-Function Input "B" Circuit', "מתג רב-תכליתי B של בקרת השיוט – תקלה במעגל", "low"),
    ("P0590", 'Cruise Control Multi-Function Input "B" Circuit Stuck', "מתג רב-תכליתי B של בקרת השיוט – תקוע", "low"),
    ("P0591", 'Cruise Control Multi-Function Input "B" Circuit Range/Performance', "מתג רב-תכליתי B של בקרת השיוט – בעיית טווח/ביצועים", "low"),
    ("P0592", 'Cruise Control Multi-Function Input "B" Circuit Low', "מתג רב-תכליתי B של בקרת השיוט – מתח נמוך במעגל", "low"),
    ("P0593", 'Cruise Control Multi-Function Input "B" Circuit High', "מתג רב-תכליתי B של בקרת השיוט – מתח גבוה במעגל", "low"),
    ("P0594", "Cruise Control Servo Control Circuit/Open", "סרבו בקרת השיוט – תקלה במעגל הבקרה / מעגל פתוח", "low"),
    ("P0595", "Cruise Control Servo Control Circuit Low", "סרבו בקרת השיוט – מתח נמוך במעגל הבקרה", "low"),
    ("P0596", "Cruise Control Servo Control Circuit High", "סרבו בקרת השיוט – מתח גבוה במעגל הבקרה", "low"),
    ("P0597", "Thermostat Heater Control Circuit/Open", "גוף חימום התרמוסטט (תרמוסטט אלקטרוני) – תקלה במעגל הבקרה / מעגל פתוח", "medium"),
    ("P0598", "Thermostat Heater Control Circuit Low", "גוף חימום התרמוסטט – מתח נמוך במעגל הבקרה", "medium"),
    ("P0599", "Thermostat Heater Control Circuit High", "גוף חימום התרמוסטט – מתח גבוה במעגל הבקרה", "medium"),
])

# ==========================================================================
# P06xx – computer and output circuits
# ==========================================================================
singles([
    ("P0600", "Serial Communication Link", "תקלה בקו התקשורת הטורית בין מחשבי הרכב", "high"),
    ("P0601", "Internal Control Module Memory Check Sum Error", "שגיאת סכום ביקורת בזיכרון מחשב המנוע", "high"),
    ("P0602", "Control Module Programming Error", "שגיאת תכנות במחשב המנוע", "high"),
    ("P0603", "Internal Control Module Keep Alive Memory (KAM) Error", "שגיאה בזיכרון השמירה (KAM) של מחשב המנוע", "medium"),
    ("P0604", "Internal Control Module Random Access Memory (RAM) Error", "שגיאת זיכרון RAM במחשב המנוע", "high"),
    ("P0605", "Internal Control Module Read Only Memory (ROM) Error", "שגיאת זיכרון ROM במחשב המנוע", "high"),
    ("P0606", "Control Module Processor", "תקלה במעבד מחשב המנוע (ECM/PCM)", "high"),
    ("P0607", "Control Module Performance", "בעיית ביצועים במחשב המנוע", "high"),
    ("P0608", 'Control Module VSS Output "A"', "מחשב המנוע – תקלה ביציאת אות מהירות A", "medium"),
    ("P0609", 'Control Module VSS Output "B"', "מחשב המנוע – תקלה ביציאת אות מהירות B", "medium"),
    ("P0610", "Control Module Vehicle Options Error", "שגיאת הגדרות/אפשרויות רכב במחשב המנוע", "medium"),
    ("P0611", "Fuel Injector Control Module Performance", "בעיית ביצועים במודול בקרת המזרקים", "high"),
    ("P0612", "Fuel Injector Control Module Relay Control", "ממסר מודול בקרת המזרקים – תקלה בבקרה", "high"),
    ("P0613", "TCM Processor", "תקלה במעבד מחשב תיבת ההילוכים (TCM)", "high"),
    ("P0614", "ECM / TCM Incompatible", "חוסר תאימות בין מחשב המנוע למחשב התיבה", "high"),
    ("P0615", "Starter Relay Circuit", "ממסר מתנע – תקלה במעגל", "medium"),
    ("P0616", "Starter Relay Circuit Low", "ממסר מתנע – מתח נמוך במעגל", "medium"),
    ("P0617", "Starter Relay Circuit High", "ממסר מתנע – מתח גבוה במעגל", "medium"),
    ("P0618", "Alternative Fuel Control Module KAM Error", "שגיאת זיכרון KAM במודול הדלק החלופי (גז)", "medium"),
    ("P0619", "Alternative Fuel Control Module RAM/ROM Error", "שגיאת זיכרון RAM/ROM במודול הדלק החלופי (גז)", "medium"),
    ("P0620", "Generator Control Circuit", "אלטרנטור – תקלה במעגל הבקרה", "high"),
    ("P0621", 'Generator Lamp/L Terminal Control Circuit', "אלטרנטור – תקלה במעגל נורית הטעינה / הדק L", "high"),
    ("P0622", 'Generator Field/F Terminal Control Circuit', "אלטרנטור – תקלה במעגל השדה / הדק F", "high"),
    ("P0623", "Generator Lamp Control Circuit", "נורית אלטרנטור – תקלה במעגל הבקרה", "medium"),
    ("P0624", "Fuel Cap Lamp Control Circuit", "נורית מכסה דלק – תקלה במעגל הבקרה", "low"),
    ("P0625", "Generator Field/F Terminal Circuit Low", "אלטרנטור – מתח נמוך במעגל השדה / הדק F", "high"),
    ("P0626", "Generator Field/F Terminal Circuit High", "אלטרנטור – מתח גבוה במעגל השדה / הדק F", "high"),
    ("P0627", 'Fuel Pump "A" Control Circuit/Open', "משאבת דלק A – תקלה במעגל הבקרה / מעגל פתוח", "high"),
    ("P0628", 'Fuel Pump "A" Control Circuit Low', "משאבת דלק A – מתח נמוך במעגל הבקרה", "high"),
    ("P0629", 'Fuel Pump "A" Control Circuit High', "משאבת דלק A – מתח גבוה במעגל הבקרה", "high"),
    ("P0630", "VIN Not Programmed or Incompatible - ECM/PCM", "מספר שלדה (VIN) לא מתוכנת או לא תואם – מחשב המנוע", "medium"),
    ("P0631", "VIN Not Programmed or Incompatible - TCM", "מספר שלדה (VIN) לא מתוכנת או לא תואם – מחשב התיבה", "medium"),
    ("P0632", "Odometer Not Programmed - ECM/PCM", "מד מרחק (אודומטר) לא מתוכנת במחשב המנוע", "low"),
    ("P0633", "Immobilizer Key Not Programmed - ECM/PCM", "מפתח אימובילייזר לא מתוכנת במחשב המנוע", "high"),
    ("P0634", "PCM/ECM/TCM Internal Temperature Too High", "טמפרטורה פנימית גבוהה מדי במחשב המנוע/התיבה", "high"),
    ("P0635", "Power Steering Control Circuit", "הגה כוח – תקלה במעגל הבקרה", "medium"),
    ("P0636", "Power Steering Control Circuit Low", "הגה כוח – מתח נמוך במעגל הבקרה", "medium"),
    ("P0637", "Power Steering Control Circuit High", "הגה כוח – מתח גבוה במעגל הבקרה", "medium"),
    ("P0638", "Throttle Actuator Control Range/Performance (Bank 1)", "מנוע המצערת החשמלית – בעיית טווח/ביצועים (בנק 1)", "high"),
    ("P0639", "Throttle Actuator Control Range/Performance (Bank 2)", "מנוע המצערת החשמלית – בעיית טווח/ביצועים (בנק 2)", "high"),
    ("P0640", "Intake Air Heater Control Circuit", "מחמם אוויר יניקה – תקלה במעגל הבקרה", "low"),
    ("P0641", 'Sensor Reference Voltage "A" Circuit/Open', "מתח ייחוס 5V לחיישנים A – תקלה במעגל / מעגל פתוח", "high"),
    ("P0642", 'Sensor Reference Voltage "A" Circuit Low', "מתח ייחוס 5V לחיישנים A – מתח נמוך", "high"),
    ("P0643", 'Sensor Reference Voltage "A" Circuit High', "מתח ייחוס 5V לחיישנים A – מתח גבוה", "high"),
    ("P0644", "Driver Display Serial Communication Circuit", "תקשורת לתצוגת הנהג – תקלה במעגל", "low"),
    ("P0645", "A/C Clutch Relay Control Circuit", "ממסר מצמד מדחס המזגן – תקלה במעגל הבקרה", "low"),
    ("P0646", "A/C Clutch Relay Control Circuit Low", "ממסר מצמד מדחס המזגן – מתח נמוך במעגל הבקרה", "low"),
    ("P0647", "A/C Clutch Relay Control Circuit High", "ממסר מצמד מדחס המזגן – מתח גבוה במעגל הבקרה", "low"),
    ("P0648", "Immobilizer Lamp Control Circuit", "נורית אימובילייזר – תקלה במעגל הבקרה", "low"),
    ("P0649", "Speed Control Lamp Control Circuit", "נורית בקרת שיוט – תקלה במעגל הבקרה", "low"),
    ("P0650", "Malfunction Indicator Lamp (MIL) Control Circuit", "נורית תקלת מנוע (Check Engine) – תקלה במעגל הבקרה", "medium"),
    ("P0651", 'Sensor Reference Voltage "B" Circuit/Open', "מתח ייחוס 5V לחיישנים B – תקלה במעגל / מעגל פתוח", "high"),
    ("P0652", 'Sensor Reference Voltage "B" Circuit Low', "מתח ייחוס 5V לחיישנים B – מתח נמוך", "high"),
    ("P0653", 'Sensor Reference Voltage "B" Circuit High', "מתח ייחוס 5V לחיישנים B – מתח גבוה", "high"),
    ("P0654", "Engine RPM Output Circuit", "יציאת אות סיבובי מנוע – תקלה במעגל", "low"),
    ("P0655", "Engine Hot Lamp Output Control Circuit", "נורית מנוע חם – תקלה במעגל הבקרה", "medium"),
    ("P0656", "Fuel Level Output Circuit", "יציאת אות מפלס דלק – תקלה במעגל", "low"),
    ("P0657", 'Actuator Supply Voltage "A" Circuit/Open', "מתח הזנה למפעילים A – תקלה במעגל / מעגל פתוח", "high"),
    ("P0658", 'Actuator Supply Voltage "A" Circuit Low', "מתח הזנה למפעילים A – מתח נמוך", "high"),
    ("P0659", 'Actuator Supply Voltage "A" Circuit High', "מתח הזנה למפעילים A – מתח גבוה", "high"),
])
series("P0660", "Intake Manifold Tuning Valve Control {} (Bank 1)", "שסתום כוונון סעפת היניקה – {} (בנק 1)",
       [("Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל הבקרה"), ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
series("P0663", "Intake Manifold Tuning Valve Control {} (Bank 2)", "שסתום כוונון סעפת היניקה – {} (בנק 2)",
       [("Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל הבקרה"), ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
series("P0666", 'PCM/ECM/TCM Internal Temperature Sensor "A"', "חיישן הטמפרטורה הפנימי של מחשב המנוע/התיבה A", S4, "medium")
add("P0670", "Glow Plug Control Module Circuit", "מודול בקרת מצתי הלהט – תקלה במעגל", "medium")
for c in range(1, 13):
    add(cs("P", 670 + c), "Cylinder %d Glow Plug Circuit" % c, "מצת להט צילינדר %d – תקלה במעגל" % c, "medium")
singles([
    ("P0683", "Glow Plug Control Module to PCM Communication Circuit", "תקשורת בין מודול מצתי הלהט למחשב המנוע – תקלה", "medium"),
    ("P0684", "Glow Plug Control Module to PCM Communication Circuit Range/Performance", "תקשורת בין מודול מצתי הלהט למחשב המנוע – בעיית טווח/ביצועים", "medium"),
    ("P0685", "ECM/PCM Power Relay Control Circuit/Open", "ממסר הזנה ראשי של מחשב המנוע – תקלה במעגל הבקרה / מעגל פתוח", "high"),
    ("P0686", "ECM/PCM Power Relay Control Circuit Low", "ממסר הזנה ראשי של מחשב המנוע – מתח נמוך במעגל הבקרה", "high"),
    ("P0687", "ECM/PCM Power Relay Control Circuit High", "ממסר הזנה ראשי של מחשב המנוע – מתח גבוה במעגל הבקרה", "high"),
    ("P0688", "ECM/PCM Power Relay Sense Circuit/Open", "ממסר הזנה ראשי של מחשב המנוע – תקלה במעגל החישה / מעגל פתוח", "high"),
    ("P0689", "ECM/PCM Power Relay Sense Circuit Low", "ממסר הזנה ראשי של מחשב המנוע – מתח נמוך במעגל החישה", "high"),
    ("P0690", "ECM/PCM Power Relay Sense Circuit High", "ממסר הזנה ראשי של מחשב המנוע – מתח גבוה במעגל החישה", "high"),
    ("P0691", "Fan 1 Control Circuit Low", "מאוורר קירור 1 – מתח נמוך במעגל הבקרה", "high"),
    ("P0692", "Fan 1 Control Circuit High", "מאוורר קירור 1 – מתח גבוה במעגל הבקרה", "high"),
    ("P0693", "Fan 2 Control Circuit Low", "מאוורר קירור 2 – מתח נמוך במעגל הבקרה", "high"),
    ("P0694", "Fan 2 Control Circuit High", "מאוורר קירור 2 – מתח גבוה במעגל הבקרה", "high"),
    ("P0695", "Fan 3 Control Circuit Low", "מאוורר קירור 3 – מתח נמוך במעגל הבקרה", "high"),
    ("P0696", "Fan 3 Control Circuit High", "מאוורר קירור 3 – מתח גבוה במעגל הבקרה", "high"),
    ("P0697", 'Sensor Reference Voltage "C" Circuit/Open', "מתח ייחוס 5V לחיישנים C – תקלה במעגל / מעגל פתוח", "high"),
    ("P0698", 'Sensor Reference Voltage "C" Circuit Low', "מתח ייחוס 5V לחיישנים C – מתח נמוך", "high"),
    ("P0699", 'Sensor Reference Voltage "C" Circuit High', "מתח ייחוס 5V לחיישנים C – מתח גבוה", "high"),
])

# ==========================================================================
# P07xx – transmission
# ==========================================================================
singles([
    ("P0700", "Transmission Control System (MIL Request)", "תקלה במערכת בקרת תיבת ההילוכים (קוד נוסף שמור במחשב התיבה)", "medium"),
    ("P0701", "Transmission Control System Range/Performance", "מערכת בקרת תיבת ההילוכים – בעיית טווח/ביצועים", "medium"),
    ("P0702", "Transmission Control System Electrical", "מערכת בקרת תיבת ההילוכים – תקלה חשמלית", "medium"),
    ("P0703", 'Brake Switch "B" Circuit', "מתג בלם B – תקלה במעגל", "medium"),
    ("P0704", "Clutch Switch Input Circuit Malfunction", "מתג דוושת מצמד – תקלה במעגל הכניסה", "low"),
])
series("P0705", 'Transmission Range Sensor "A" {} (PRNDL Input)', "חיישן מצב בורר ההילוכים A (PRNDL) – {}",
       ["c", "rp", "l", "h", "i"], "medium")
series("P0710", 'Transmission Fluid Temperature Sensor "A"', "חיישן טמפרטורת שמן הגיר A", S5, "medium")
series("P0715", 'Input/Turbine Speed Sensor "A"', "חיישן מהירות כניסה/טורבינה A של התיבה", SPD4, "medium")
add("P0719", 'Brake Switch "B" Circuit Low', "מתג בלם B – מתח נמוך במעגל", "medium")
series("P0720", "Output Speed Sensor", "חיישן מהירות יציאה של התיבה", SPD4, "medium")
add("P0724", 'Brake Switch "B" Circuit High', "מתג בלם B – מתח גבוה במעגל", "medium")
series("P0725", "Engine Speed Input", "אות סיבובי מנוע לתיבה", SPD4, "medium")
add("P0729", "Gear 6 Incorrect Ratio", "יחס העברה שגוי בהילוך 6 (החלקה)", "high")
add("P0730", "Incorrect Gear Ratio", "יחס העברה שגוי (החלקה בתיבת ההילוכים)", "high")
for g in range(1, 6):
    add(cs("P", 730 + g), "Gear %d Incorrect Ratio" % g, "יחס העברה שגוי בהילוך %d (החלקה)" % g, "high")
singles([
    ("P0736", "Reverse Incorrect Ratio", "יחס העברה שגוי בהילוך אחורי", "high"),
    ("P0737", "TCM Engine Speed Output Circuit", "מחשב התיבה – תקלה ביציאת אות סיבובי מנוע", "medium"),
    ("P0738", "TCM Engine Speed Output Circuit Low", "מחשב התיבה – מתח נמוך ביציאת אות סיבובי מנוע", "medium"),
    ("P0739", "TCM Engine Speed Output Circuit High", "מחשב התיבה – מתח גבוה ביציאת אות סיבובי מנוע", "medium"),
])
series("P0740", "Torque Converter Clutch Solenoid", "סולנואיד נעילת ממיר המומנט (TCC)",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), "pso", "son", "el", "int"], "medium")
series("P0745", 'Pressure Control Solenoid "A"', "סולנואיד בקרת לחץ A בתיבה", SOL5, "medium")
for k, base in enumerate((750, 755, 760, 765, 770)):
    series(cs("P", base), 'Shift Solenoid "%s"' % LETTERS[k], "סולנואיד העברת הילוכים %s" % LETTERS[k], SOL5, "medium")
series("P0775", 'Pressure Control Solenoid "B"', "סולנואיד בקרת לחץ B בתיבה", SOL5, "medium")
singles([
    ("P0780", "Shift Error", "שגיאה בהעברת הילוכים", "medium"),
    ("P0781", "1-2 Shift", "תקלה בהעברה מהילוך 1 ל-2", "medium"),
    ("P0782", "2-3 Shift", "תקלה בהעברה מהילוך 2 ל-3", "medium"),
    ("P0783", "3-4 Shift", "תקלה בהעברה מהילוך 3 ל-4", "medium"),
    ("P0784", "4-5 Shift", "תקלה בהעברה מהילוך 4 ל-5", "medium"),
])
series("P0785", "Shift/Timing Solenoid", "סולנואיד תזמון העברת הילוכים", ["base", "rp2", "lo", "hi", "int"], "medium")
add("P0790", "Normal/Performance Switch Circuit", "מתג מצב נהיגה רגיל/ספורט – תקלה במעגל", "low")
series("P0791", 'Intermediate Shaft Speed Sensor "A"', "חיישן מהירות הציר הביניים A בתיבה", SPD4, "medium")
series("P0795", 'Pressure Control Solenoid "C"', "סולנואיד בקרת לחץ C בתיבה", SOL5, "medium")

# ==========================================================================
# P08xx – transmission
# ==========================================================================
singles([
    ("P0801", "Reverse Inhibit Control Circuit", "מעגל מניעת הילוך אחורי – תקלה", "medium"),
    ("P0802", "Transmission Control System MIL Request Circuit/Open", "מעגל בקשת נורית תקלה ממחשב התיבה – תקלה / מעגל פתוח", "low"),
    ("P0803", "1-4 Upshift (Skip Shift) Solenoid Control Circuit", "סולנואיד דילוג הילוך 1-4 – תקלה במעגל הבקרה", "low"),
    ("P0804", "1-4 Upshift (Skip Shift) Lamp Control Circuit", "נורית דילוג הילוך 1-4 – תקלה במעגל הבקרה", "low"),
])
series("P0805", "Clutch Position Sensor", "חיישן מצב המצמד", S5, "medium")
singles([
    ("P0810", "Clutch Position Control Error", "שגיאה בבקרת מצב המצמד", "medium"),
    ("P0811", "Excessive Clutch Slippage", "החלקת מצמד מוגזמת", "high"),
    ("P0812", "Reverse Input Circuit", "מעגל אות הילוך אחורי – תקלה", "low"),
    ("P0813", "Reverse Output Circuit", "מעגל יציאת הילוך אחורי – תקלה", "low"),
    ("P0814", "Transmission Range Display Circuit", "תצוגת מצב בורר ההילוכים – תקלה במעגל", "low"),
    ("P0815", "Upshift Switch Circuit", "מתג העלאת הילוך – תקלה במעגל", "low"),
    ("P0816", "Downshift Switch Circuit", "מתג הורדת הילוך – תקלה במעגל", "low"),
    ("P0817", "Starter Disable Circuit", "מעגל מניעת התנעה – תקלה", "medium"),
    ("P0818", "Driveline Disconnect Switch Input Circuit", "מתג ניתוק ההנעה – תקלה במעגל הכניסה", "medium"),
    ("P0820", "Gear Lever X-Y Position Sensor Circuit", "חיישן מיקום ידית ההילוכים X-Y – תקלה במעגל", "medium"),
    ("P0821", "Gear Lever X Position Circuit", "חיישן מיקום ידית ההילוכים X – תקלה במעגל", "medium"),
    ("P0822", "Gear Lever Y Position Circuit", "חיישן מיקום ידית ההילוכים Y – תקלה במעגל", "medium"),
    ("P0823", "Gear Lever X Position Circuit Intermittent", "חיישן מיקום ידית ההילוכים X – תקלה לסירוגין", "medium"),
    ("P0824", "Gear Lever Y Position Circuit Intermittent", "חיישן מיקום ידית ההילוכים Y – תקלה לסירוגין", "medium"),
    ("P0825", "Gear Lever Push-Pull Switch (Shift Anticipate)", "מתג דחיפה/משיכה בידית ההילוכים – תקלה", "low"),
    ("P0826", "Up and Down Shift Switch Circuit", "מתגי העלאת/הורדת הילוך – תקלה במעגל", "low"),
    ("P0827", "Up and Down Shift Switch Circuit Low", "מתגי העלאת/הורדת הילוך – מתח נמוך במעגל", "low"),
    ("P0828", "Up and Down Shift Switch Circuit High", "מתגי העלאת/הורדת הילוך – מתח גבוה במעגל", "low"),
    ("P0829", "5-6 Shift", "תקלה בהעברה מהילוך 5 ל-6", "medium"),
])
series("P0830", 'Clutch Pedal Switch "A"', "מתג דוושת מצמד A", ["c", "l", "h"], "low")
series("P0833", 'Clutch Pedal Switch "B"', "מתג דוושת מצמד B", ["c", "l", "h"], "low")
series("P0836", "Four Wheel Drive (4WD) Switch", "מתג הנעה כפולה (4X4)", S4, "medium")
series("P0840", 'Transmission Fluid Pressure Sensor/Switch "A"', "חיישן/מתג לחץ שמן הגיר A", S5, "medium")
series("P0845", 'Transmission Fluid Pressure Sensor/Switch "B"', "חיישן/מתג לחץ שמן הגיר B", S5, "medium")
series("P0850", "Park/Neutral Switch Input", "מתג חניה/סרק (P/N)", ["c", "l", "h"], "medium")
series("P0853", "Drive Switch Input", "מתג מצב נסיעה (D)", ["c", "l", "h"], "medium")
series("P0856", "Traction Control Input Signal", "אות כניסה ממערכת בקרת המשיכה",
       [("", "תקלה"), "rp2", "lo", "hi"], "medium")
series("P0860", "Gear Shift Module Communication", "תקשורת עם מודול ידית ההילוכים", ["c", "l", "h"], "medium")
series("P0863", "TCM Communication", "תקשורת עם מחשב התיבה (TCM)", S4, "medium")
singles([
    ("P0867", "Transmission Fluid Pressure", "לחץ שמן הגיר לא תקין", "high"),
    ("P0868", "Transmission Fluid Pressure Low", "לחץ שמן הגיר נמוך", "high"),
    ("P0869", "Transmission Fluid Pressure High", "לחץ שמן הגיר גבוה", "medium"),
])
series("P0870", 'Transmission Fluid Pressure Sensor/Switch "C"', "חיישן/מתג לחץ שמן הגיר C", S5, "medium")
series("P0875", 'Transmission Fluid Pressure Sensor/Switch "D"', "חיישן/מתג לחץ שמן הגיר D", S5, "medium")
series("P0880", "TCM Power Input Signal", "אות הזנת מתח למחשב התיבה",
       [("", "תקלה"), "rp2", "lo", "hi", "int"], "medium")
series("P0885", "TCM Power Relay Control", "ממסר הזנה של מחשב התיבה", [("Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל הבקרה"), ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
series("P0888", "TCM Power Relay Sense", "מעגל החישה של ממסר ההזנה במחשב התיבה", S5, "medium")
singles([
    ("P0893", "Multiple Gears Engaged", "כמה הילוכים משולבים בו-זמנית", "stop"),
    ("P0894", "Transmission Component Slipping", "החלקה של רכיב בתיבת ההילוכים", "high"),
    ("P0895", "Shift Time Too Short", "זמן העברת הילוך קצר מדי", "medium"),
    ("P0896", "Shift Time Too Long", "זמן העברת הילוך ארוך מדי", "medium"),
    ("P0897", "Transmission Fluid Deteriorated", "שמן הגיר פגום/בלוי", "high"),
    ("P0898", "Transmission Control System MIL Request Circuit Low", "מעגל בקשת נורית תקלה ממחשב התיבה – מתח נמוך", "low"),
    ("P0899", "Transmission Control System MIL Request Circuit High", "מעגל בקשת נורית תקלה ממחשב התיבה – מתח גבוה", "low"),
])

# ==========================================================================
# P09xx – transmission (automated manual / hydraulics)
# ==========================================================================
series("P0900", "Clutch Actuator", "מפעיל המצמד", ["o", "rp", "l", "h"], "medium")
series("P0904", "Gate Select Position", "חיישן מיקום בחירת מסלול ההילוכים", S5, "medium")
add("P0909", "Gate Select Control Error", "שגיאה בבקרת בחירת מסלול ההילוכים", "medium")
series("P0910", "Gate Select Actuator", "מפעיל בחירת מסלול ההילוכים", ["o", "rp", "l", "h"], "medium")
series("P0914", "Gear Shift Position", "חיישן מיקום העברת ההילוכים", S5, "medium")
add("P0919", "Gear Shift Position Control Error", "שגיאה בבקרת מיקום העברת ההילוכים", "medium")
series("P0920", "Gear Shift Forward Actuator", "מפעיל העברת הילוך קדימה", ["o", "rp", "l", "h"], "medium")
series("P0924", "Gear Shift Reverse Actuator", "מפעיל העברת הילוך אחורה", ["o", "rp", "l", "h"], "medium")
series("P0928", "Gear Shift Lock Solenoid Control", "סולנואיד נעילת ידית ההילוכים",
       [("Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"), ("Circuit Range/Performance", "בעיית טווח/ביצועים"), ("Circuit Low", "מתח נמוך במעגל הבקרה"), ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
series("P0932", "Hydraulic Pressure Sensor", "חיישן לחץ הידראולי בתיבה", S5, "medium")
series("P0937", "Hydraulic Oil Temperature Sensor", "חיישן טמפרטורת שמן הידראולי", S5, "medium")
singles([
    ("P0942", "Hydraulic Pressure Unit", "יחידת הלחץ ההידראולי – תקלה", "high"),
    ("P0943", "Hydraulic Pressure Unit Cycling Period Too Short", "יחידת הלחץ ההידראולי – מחזורי הפעלה קצרים מדי", "medium"),
    ("P0944", "Hydraulic Pressure Unit Loss of Pressure", "יחידת הלחץ ההידראולי – איבוד לחץ", "high"),
])
series("P0945", "Hydraulic Pump Relay", "ממסר המשאבה ההידראולית", ["o", "rp", "l", "h"], "medium")
add("P0949", "Auto Shift Manual Adaptive Learning Not Complete", "לימוד אדפטיבי של תיבה רובוטית לא הושלם", "low")
series("P0950", "Auto Shift Manual Control", "בקרת העברה ידנית (טיפטרוניק)", S5, "low")
series("P0955", "Auto Shift Manual Mode", "מצב העברה ידנית (טיפטרוניק)", S5, "low")
for k, base in enumerate((960, 964, 968)):
    series(cs("P", base), 'Pressure Control Solenoid "%s"' % LETTERS[k], "סולנואיד בקרת לחץ %s" % LETTERS[k], CO4, "medium")
for k, base in enumerate((972, 975, 978, 981, 984)):
    series(cs("P", base), 'Shift Solenoid "%s"' % LETTERS[k], "סולנואיד העברת הילוכים %s" % LETTERS[k], ["crp", "cl", "ch"], "medium")
series("P0987", 'Transmission Fluid Pressure Sensor/Switch "E"', "חיישן/מתג לחץ שמן הגיר E", S5, "medium")
series("P0992", 'Transmission Fluid Pressure Sensor/Switch "F"', "חיישן/מתג לחץ שמן הגיר F", S5, "medium")
series("P0997", 'Shift Solenoid "F"', "סולנואיד העברת הילוכים F", ["crp", "cl", "ch"], "medium")

# ==========================================================================
# P0Axx – hybrid / electric propulsion (selected generic codes)
# ==========================================================================
singles([
    ("P0A00", "Motor Electronics Coolant Temperature Sensor Circuit", "חיישן טמפרטורת נוזל קירור של אלקטרוניקת המנוע החשמלי – תקלה במעגל", "medium"),
    ("P0A01", "Motor Electronics Coolant Temperature Sensor Circuit Range/Performance", "חיישן טמפרטורת קירור אלקטרוניקת המנוע החשמלי – בעיית טווח/ביצועים", "medium"),
    ("P0A02", "Motor Electronics Coolant Temperature Sensor Circuit Low", "חיישן טמפרטורת קירור אלקטרוניקת המנוע החשמלי – מתח נמוך", "medium"),
    ("P0A03", "Motor Electronics Coolant Temperature Sensor Circuit High", "חיישן טמפרטורת קירור אלקטרוניקת המנוע החשמלי – מתח גבוה", "medium"),
    ("P0A04", "Motor Electronics Coolant Temperature Sensor Circuit Intermittent", "חיישן טמפרטורת קירור אלקטרוניקת המנוע החשמלי – תקלה לסירוגין", "medium"),
    ("P0A05", "Motor Electronics Coolant Pump Control Circuit/Open", "משאבת קירור אלקטרוניקת המנוע החשמלי – תקלה במעגל הבקרה", "high"),
    ("P0A06", "Motor Electronics Coolant Pump Control Circuit Low", "משאבת קירור אלקטרוניקת המנוע החשמלי – מתח נמוך במעגל הבקרה", "high"),
    ("P0A07", "Motor Electronics Coolant Pump Control Circuit High", "משאבת קירור אלקטרוניקת המנוע החשמלי – מתח גבוה במעגל הבקרה", "high"),
    ("P0A08", "DC/DC Converter Status Circuit", "ממיר DC/DC – תקלה במעגל הסטטוס", "high"),
    ("P0A09", "DC/DC Converter Status Circuit Low", "ממיר DC/DC – מתח נמוך במעגל הסטטוס", "high"),
    ("P0A0A", "High Voltage System Interlock Circuit", "מעגל נעילת הבטיחות של מערכת המתח הגבוה – תקלה", "stop"),
    ("P0A0F", "Engine Failed to Start", "המנוע לא הצליח להתניע (רכב היברידי)", "high"),
    ("P0A10", "DC/DC Converter Status Circuit High", "ממיר DC/DC – מתח גבוה במעגל הסטטוס", "high"),
    ("P0A0B", "High Voltage System Interlock Circuit Range/Performance", "מעגל נעילת הבטיחות של המתח הגבוה – בעיית טווח/ביצועים", "stop"),
    ("P0A0C", "High Voltage System Interlock Circuit Low", "מעגל נעילת הבטיחות של המתח הגבוה – מתח נמוך", "stop"),
    ("P0A0D", "High Voltage System Interlock Circuit High", "מעגל נעילת הבטיחות של המתח הגבוה – מתח גבוה", "stop"),
    ("P0A0E", "High Voltage System Interlock Circuit Intermittent", "מעגל נעילת הבטיחות של המתח הגבוה – תקלה לסירוגין", "stop"),
    ("P0A78", 'Drive Motor "A" Inverter Performance', "בעיית ביצועים בממיר (אינוורטר) של מנוע ההנעה A", "stop"),
    ("P0A7F", "Hybrid Battery Pack Deterioration", "התבלות מצבר ההנעה ההיברידי", "high"),
    ("P0A80", "Replace Hybrid Battery Pack", "יש להחליף את מצבר ההנעה ההיברידי", "high"),
    ("P0A93", 'Inverter "A" Cooling System Performance', "בעיית ביצועים במערכת הקירור של הממיר (אינוורטר) A", "high"),
    ("P0A94", "DC/DC Converter Performance", "בעיית ביצועים בממיר DC/DC (מגבר מתח)", "high"),
    ("P0AA6", "Hybrid Battery Voltage System Isolation Fault", "תקלת בידוד במערכת המתח הגבוה של המצבר ההיברידי", "stop"),
])

# ==========================================================================
# P20xx
# ==========================================================================
singles([
    ("P2000", "NOx Trap Efficiency Below Threshold (Bank 1)", "יעילות מלכודת ה-NOx נמוכה מהסף (בנק 1)", "medium"),
    ("P2001", "NOx Trap Efficiency Below Threshold (Bank 2)", "יעילות מלכודת ה-NOx נמוכה מהסף (בנק 2)", "medium"),
    ("P2002", "Diesel Particulate Filter Efficiency Below Threshold (Bank 1)", "יעילות מסנן החלקיקים (DPF) נמוכה מהסף (בנק 1)", "medium"),
    ("P2003", "Diesel Particulate Filter Efficiency Below Threshold (Bank 2)", "יעילות מסנן החלקיקים (DPF) נמוכה מהסף (בנק 2)", "medium"),
    ("P2004", "Intake Manifold Runner Control Stuck Open (Bank 1)", "דשי מסלולי סעפת היניקה תקועים במצב פתוח (בנק 1)", "medium"),
    ("P2005", "Intake Manifold Runner Control Stuck Open (Bank 2)", "דשי מסלולי סעפת היניקה תקועים במצב פתוח (בנק 2)", "medium"),
    ("P2006", "Intake Manifold Runner Control Stuck Closed (Bank 1)", "דשי מסלולי סעפת היניקה תקועים במצב סגור (בנק 1)", "medium"),
    ("P2007", "Intake Manifold Runner Control Stuck Closed (Bank 2)", "דשי מסלולי סעפת היניקה תקועים במצב סגור (בנק 2)", "medium"),
])
for base, b in ((2008, 1), (2011, 2)):
    series(cs("P", base), "Intake Manifold Runner Control {} (Bank %d)" % b, "בקרת דשי סעפת היניקה – {} (בנק %d)" % b,
           [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
for base, b in ((2014, 1), (2019, 2)):
    series(cs("P", base), "Intake Manifold Runner Position Sensor/Switch {} (Bank %d)" % b,
           "חיישן מיקום דשי סעפת היניקה – {} (בנק %d)" % b, S5, "medium")
series("P2024", "Evaporative Emissions (EVAP) Fuel Vapor Temperature Sensor",
       "חיישן טמפרטורת אדי הדלק במערכת EVAP",
       ["c", ("Circuit Performance", "בעיית ביצועים"), "lv", "hv", "i"], "low")
singles([
    ("P2029", "Fuel Fired Heater Disabled", "מחמם הדלק (חימום עזר) מושבת", "low"),
    ("P2030", "Fuel Fired Heater Performance", "בעיית ביצועים במחמם הדלק (חימום עזר)", "low"),
])
series("P2031", "Exhaust Gas Temperature Sensor {} " + bs_en(1, 2), "חיישן טמפרטורת גזי פליטה (EGT) – {} " + bs_he(1, 2), ["c", "l", "h"], "medium")
series("P2034", "Exhaust Gas Temperature Sensor {} " + bs_en(2, 2), "חיישן טמפרטורת גזי פליטה (EGT) – {} " + bs_he(2, 2), ["c", "l", "h"], "medium")
series("P2037", "Reductant Injection Air Pressure Sensor", "חיישן לחץ אוויר להזרקת AdBlue", S5, "medium")
series("P2042", "Reductant Temperature Sensor", "חיישן טמפרטורת AdBlue", S5, "medium")
for base, b, u in ((2047, 1, 1), (2050, 2, 1), (2053, 1, 2), (2056, 2, 2)):
    series(cs("P", base), "Reductant Injector {} (Bank %d Unit %d)" % (b, u), "מזרק AdBlue – {} (בנק %d יחידה %d)" % (b, u),
           [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
series("P2059", "Reductant Injection Air Pump", "משאבת האוויר להזרקת AdBlue", CO3, "medium")
series("P2062", "Reductant Supply", "אספקת AdBlue", CO3, "medium")
series("P2065", 'Fuel Level Sensor "B"', "חיישן מפלס דלק B", S5, "low")
singles([
    ("P2070", "Intake Manifold Tuning (IMT) Valve Stuck Open", "שסתום כוונון סעפת היניקה (IMT) תקוע במצב פתוח", "medium"),
    ("P2071", "Intake Manifold Tuning (IMT) Valve Stuck Closed", "שסתום כוונון סעפת היניקה (IMT) תקוע במצב סגור", "medium"),
])
series("P2075", "Intake Manifold Tuning (IMT) Valve Position Sensor/Switch", "חיישן מיקום שסתום כוונון סעפת היניקה (IMT)", S5, "medium")
for base, b, s in ((2080, 1, 1), (2082, 2, 1), (2084, 1, 2), (2086, 2, 2)):
    series(cs("P", base), "Exhaust Gas Temperature Sensor {} " + bs_en(b, s), "חיישן טמפרטורת גזי פליטה (EGT) – {} " + bs_he(b, s),
           ["rp", "i"], "medium")
for k, (cam, b) in enumerate((("A", 1), ("B", 1), ("A", 2), ("B", 2))):
    n = 2088 + k * 2
    add(cs("P", n), '"%s" Camshaft Position Actuator Control Circuit Low (Bank %d)' % (cam, b),
        "מפעיל תזמון גל זיזים %s (VVT) – מתח נמוך במעגל הבקרה (בנק %d)" % (cam, b), "medium")
    add(cs("P", n + 1), '"%s" Camshaft Position Actuator Control Circuit High (Bank %d)' % (cam, b),
        "מפעיל תזמון גל זיזים %s (VVT) – מתח גבוה במעגל הבקרה (בנק %d)" % (cam, b), "medium")
singles([
    ("P2096", "Post Catalyst Fuel Trim System Too Lean (Bank 1)", "תיקון תערובת אחרי הממיר – דלה מדי (בנק 1)", "medium"),
    ("P2097", "Post Catalyst Fuel Trim System Too Rich (Bank 1)", "תיקון תערובת אחרי הממיר – עשירה מדי (בנק 1)", "medium"),
    ("P2098", "Post Catalyst Fuel Trim System Too Lean (Bank 2)", "תיקון תערובת אחרי הממיר – דלה מדי (בנק 2)", "medium"),
    ("P2099", "Post Catalyst Fuel Trim System Too Rich (Bank 2)", "תיקון תערובת אחרי הממיר – עשירה מדי (בנק 2)", "medium"),
])

# ==========================================================================
# P21xx – throttle actuator control, pedal sensors, fuel trim
# ==========================================================================
series("P2100", "Throttle Actuator Control Motor", "מנוע המצערת החשמלית (TAC)",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), "rp", "l", "h"], "high")
singles([
    ("P2104", "Throttle Actuator Control System - Forced Idle", "מערכת המצערת החשמלית – מצב חירום: סרק כפוי", "high"),
    ("P2105", "Throttle Actuator Control System - Forced Engine Shutdown", "מערכת המצערת החשמלית – כיבוי מנוע כפוי", "stop"),
    ("P2106", "Throttle Actuator Control System - Forced Limited Power", "מערכת המצערת החשמלית – מצב חירום: הגבלת כוח (Limp Mode)", "high"),
    ("P2107", "Throttle Actuator Control Module Processor", "מודול בקרת המצערת – תקלה במעבד", "high"),
    ("P2108", "Throttle Actuator Control Module Performance", "מודול בקרת המצערת – בעיית ביצועים", "high"),
    ("P2109", 'Throttle/Pedal Position Sensor "A" Minimum Stop Performance', "חיישן מצערת/דוושה A – בעיה בנקודת המינימום (עצירה תחתונה)", "high"),
    ("P2110", "Throttle Actuator Control System - Forced Limited RPM", "מערכת המצערת החשמלית – הגבלת סיבובי מנוע כפויה", "high"),
    ("P2111", "Throttle Actuator Control System - Stuck Open", "המצערת החשמלית תקועה במצב פתוח", "stop"),
    ("P2112", "Throttle Actuator Control System - Stuck Closed", "המצערת החשמלית תקועה במצב סגור", "high"),
])
for k, (ch, code) in enumerate((("B", "P2113"), ("C", "P2114"), ("D", "P2115"), ("E", "P2116"), ("F", "P2117"))):
    add(code, 'Throttle/Pedal Position Sensor "%s" Minimum Stop Performance' % ch,
        "חיישן מצערת/דוושה %s – בעיה בנקודת המינימום (עצירה תחתונה)" % ch, "high")
singles([
    ("P2118", "Throttle Actuator Control Motor Current Range/Performance", "מנוע המצערת החשמלית – זרם חריג (טווח/ביצועים)", "high"),
    ("P2119", "Throttle Actuator Control Throttle Body Range/Performance", "גוף המצערת החשמלית – בעיית טווח/ביצועים", "high"),
])
for ch, base in (("D", 2120), ("E", 2125), ("F", 2130)):
    series(cs("P", base), 'Throttle/Pedal Position Sensor/Switch "%s"' % ch, "חיישן מצב מצערת/דוושה %s (דוושת גז)" % ch, S5, "high")
for code, pair in (("P2135", "A/B"), ("P2136", "A/C"), ("P2137", "B/C"), ("P2138", "D/E"), ("P2139", "D/F"), ("P2140", "E/F")):
    a, b = pair.split("/")
    add(code, 'Throttle/Pedal Position Sensor/Switch "%s"/"%s" Voltage Correlation' % (a, b),
        "חוסר התאמה במתח בין חיישני מצערת/דוושה %s ו-%s" % (a, b), "high")
singles([
    ("P2141", "Exhaust Gas Recirculation Throttle Control Circuit Low", "מצערת EGR – מתח נמוך במעגל הבקרה", "medium"),
    ("P2142", "Exhaust Gas Recirculation Throttle Control Circuit High", "מצערת EGR – מתח גבוה במעגל הבקרה", "medium"),
])
series("P2143", "Exhaust Gas Recirculation Vent", "אוורור EGR", CO3, "medium")
for k, base in enumerate((2146, 2149, 2152, 2155)):
    series(cs("P", base), 'Fuel Injector Group "%s" Supply Voltage' % LETTERS[k], "מתח הזנה לקבוצת מזרקים %s" % LETTERS[k],
           [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "high")
series("P2158", 'Vehicle Speed Sensor "B"', "חיישן מהירות רכב B",
       ["base", "rp2", ("Low", "אות נמוך"), ("Intermittent/Erratic/High", "אות לסירוגין / לא יציב / גבוה")], "medium")
add("P2162", 'Vehicle Speed Sensor "A"/"B" Correlation', "חוסר התאמה בין חיישני מהירות הרכב A ו-B", "medium")
for ch, code in zip("ABCDEF", ("P2163", "P2164", "P2165", "P2166", "P2167", "P2168")):
    add(code, 'Throttle/Pedal Position Sensor "%s" Maximum Stop Performance' % ch,
        "חיישן מצערת/דוושה %s – בעיה בנקודת המקסימום (עצירה עליונה)" % ch, "high")
series("P2169", "Exhaust Pressure Regulator Vent Solenoid", "סולנואיד אוורור של וסת לחץ הפליטה", CO3, "medium")
singles([
    ("P2172", "Throttle Actuator Control System - Sudden High Airflow Detected", "מערכת המצערת – זוהתה עלייה פתאומית בזרימת האוויר", "high"),
    ("P2173", "Throttle Actuator Control System - High Airflow Detected", "מערכת המצערת – זוהתה זרימת אוויר גבוהה", "high"),
    ("P2174", "Throttle Actuator Control System - Sudden Low Airflow Detected", "מערכת המצערת – זוהתה ירידה פתאומית בזרימת האוויר", "high"),
    ("P2175", "Throttle Actuator Control System - Low Airflow Detected", "מערכת המצערת – זוהתה זרימת אוויר נמוכה", "high"),
    ("P2176", "Throttle Actuator Control System - Idle Position Not Learned", "מערכת המצערת – מיקום סרק לא נלמד (נדרש לימוד מצערת)", "medium"),
    ("P2177", "System Too Lean Off Idle (Bank 1)", "תערובת דלה מדי מחוץ לסרק (בנק 1)", "medium"),
    ("P2178", "System Too Rich Off Idle (Bank 1)", "תערובת עשירה מדי מחוץ לסרק (בנק 1)", "medium"),
    ("P2179", "System Too Lean Off Idle (Bank 2)", "תערובת דלה מדי מחוץ לסרק (בנק 2)", "medium"),
    ("P2180", "System Too Rich Off Idle (Bank 2)", "תערובת עשירה מדי מחוץ לסרק (בנק 2)", "medium"),
    ("P2181", "Cooling System Performance", "בעיית ביצועים במערכת הקירור", "high"),
])
series("P2182", "Engine Coolant Temperature Sensor 2", "חיישן טמפרטורת נוזל קירור 2", S5, "medium")
singles([
    ("P2187", "System Too Lean at Idle (Bank 1)", "תערובת דלה מדי בסרק (בנק 1)", "medium"),
    ("P2188", "System Too Rich at Idle (Bank 1)", "תערובת עשירה מדי בסרק (בנק 1)", "medium"),
    ("P2189", "System Too Lean at Idle (Bank 2)", "תערובת דלה מדי בסרק (בנק 2)", "medium"),
    ("P2190", "System Too Rich at Idle (Bank 2)", "תערובת עשירה מדי בסרק (בנק 2)", "medium"),
    ("P2191", "System Too Lean at Higher Load (Bank 1)", "תערובת דלה מדי בעומס גבוה (בנק 1)", "medium"),
    ("P2192", "System Too Rich at Higher Load (Bank 1)", "תערובת עשירה מדי בעומס גבוה (בנק 1)", "medium"),
    ("P2193", "System Too Lean at Higher Load (Bank 2)", "תערובת דלה מדי בעומס גבוה (בנק 2)", "medium"),
    ("P2194", "System Too Rich at Higher Load (Bank 2)", "תערובת עשירה מדי בעומס גבוה (בנק 2)", "medium"),
    ("P2195", "O2 Sensor Signal Biased/Stuck Lean (Bank 1 Sensor 1)", "אות חיישן O2 תקוע/מוטה לכיוון דל (בנק 1 חיישן 1)", "medium"),
    ("P2196", "O2 Sensor Signal Biased/Stuck Rich (Bank 1 Sensor 1)", "אות חיישן O2 תקוע/מוטה לכיוון עשיר (בנק 1 חיישן 1)", "medium"),
    ("P2197", "O2 Sensor Signal Biased/Stuck Lean (Bank 2 Sensor 1)", "אות חיישן O2 תקוע/מוטה לכיוון דל (בנק 2 חיישן 1)", "medium"),
    ("P2198", "O2 Sensor Signal Biased/Stuck Rich (Bank 2 Sensor 1)", "אות חיישן O2 תקוע/מוטה לכיוון עשיר (בנק 2 חיישן 1)", "medium"),
    ("P2199", "Intake Air Temperature Sensor 1 / 2 Correlation", "חוסר התאמה בין חיישני טמפרטורת אוויר היניקה 1 ו-2", "medium"),
])

# ==========================================================================
# P22xx – NOx sensors, baro, O2 sensor circuits, misc
# ==========================================================================
NOXFT = ["c", "rp", "li", "hin", "i"]
for b, base_c, base_h, base_s in ((1, 2200, 2205, 2208), (2, 2213, 2218, 2221)):
    series(cs("P", base_c), "NOx Sensor {} (Bank %d)" % b, "חיישן NOx – {} (בנק %d)" % b, NOXFT, "medium")
    series(cs("P", base_h), "NOx Sensor Heater Control {} (Bank %d)" % b, "גוף חימום חיישן NOx – {} (בנק %d)" % b,
           [("Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל הבקרה"), ("Circuit High", "מתח גבוה במעגל הבקרה")], "medium")
    series(cs("P", base_s), "NOx Sensor Heater Sense {} (Bank %d)" % b, "גוף חימום חיישן NOx, מעגל חישה – {} (בנק %d)" % b, S5, "medium")
series("P2226", "Barometric Pressure", "חיישן לחץ ברומטרי", S5, "medium")
for k, (b, s) in enumerate(((1, 1), (1, 2), (1, 3), (2, 1), (2, 2), (2, 3))):
    add(cs("P", 2231 + k), "O2 Sensor Signal Circuit Shorted to Heater Circuit " + bs_en(b, s),
        "קצר בין מעגל האות למעגל החימום של חיישן O2 " + bs_he(b, s), "medium")
_o2cur = [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")]
series("P2237", "O2 Sensor Positive Current Control {} " + bs_en(1, 1), "בקרת זרם חיובי בחיישן O2 – {} " + bs_he(1, 1), _o2cur, "medium")
series("P2240", "O2 Sensor Positive Current Control {} " + bs_en(2, 1), "בקרת זרם חיובי בחיישן O2 – {} " + bs_he(2, 1), _o2cur, "medium")
_o2ref = [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Range/Performance", "בעיית טווח/ביצועים"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")]
series("P2243", "O2 Sensor Reference Voltage {} " + bs_en(1, 1), "מתח ייחוס של חיישן O2 – {} " + bs_he(1, 1), _o2ref, "medium")
series("P2247", "O2 Sensor Reference Voltage {} " + bs_en(2, 1), "מתח ייחוס של חיישן O2 – {} " + bs_he(2, 1), _o2ref, "medium")
series("P2251", "O2 Sensor Negative Current Control {} " + bs_en(1, 1), "בקרת זרם שלילי בחיישן O2 – {} " + bs_he(1, 1), _o2cur, "medium")
series("P2254", "O2 Sensor Negative Current Control {} " + bs_en(2, 1), "בקרת זרם שלילי בחיישן O2 – {} " + bs_he(2, 1), _o2cur, "medium")
singles([
    ("P2257", 'Secondary Air Injection System Control "A" Circuit Low', "בקרת אוויר משני A – מתח נמוך במעגל", "low"),
    ("P2258", 'Secondary Air Injection System Control "A" Circuit High', "בקרת אוויר משני A – מתח גבוה במעגל", "low"),
    ("P2259", 'Secondary Air Injection System Control "B" Circuit Low', "בקרת אוויר משני B – מתח נמוך במעגל", "low"),
    ("P2260", 'Secondary Air Injection System Control "B" Circuit High', "בקרת אוויר משני B – מתח גבוה במעגל", "low"),
    ("P2261", "Turbocharger/Supercharger Bypass Valve - Mechanical", "שסתום עקיפה (Bypass) של הטורבו – תקלה מכנית", "medium"),
    ("P2262", "Turbocharger/Supercharger Boost Pressure Not Detected - Mechanical", "לא זוהה לחץ טורבו – תקלה מכנית", "medium"),
    ("P2263", "Turbocharger/Supercharger Boost System Performance", "בעיית ביצועים במערכת לחץ הטורבו", "medium"),
])
series("P2264", "Water in Fuel Sensor", "חיישן מים בדלק", S5, "medium")
add("P2269", "Water in Fuel Condition", "זוהו מים בדלק", "high")
for k, (b, s) in enumerate(((1, 2), (2, 2), (1, 3), (2, 3))):
    add(cs("P", 2270 + 2 * k), "O2 Sensor Signal Biased/Stuck Lean " + bs_en(b, s),
        "אות חיישן O2 תקוע/מוטה לכיוון דל " + bs_he(b, s), "medium")
    add(cs("P", 2271 + 2 * k), "O2 Sensor Signal Biased/Stuck Rich " + bs_en(b, s),
        "אות חיישן O2 תקוע/מוטה לכיוון עשיר " + bs_he(b, s), "medium")
singles([
    ("P2278", "O2 Sensor Signals Swapped Bank 1 Sensor 3 / Bank 2 Sensor 3", "אותות חיישני O2 מוחלפים – בנק 1 חיישן 3 / בנק 2 חיישן 3", "medium"),
    ("P2279", "Intake Air System Leak", "דליפת אוויר במערכת היניקה", "medium"),
    ("P2280", "Air Flow Restriction / Air Leak Between Air Filter and MAF", "חסימת אוויר או דליפה בין מסנן האוויר לחיישן ה-MAF", "medium"),
    ("P2281", "Air Leak Between MAF and Throttle Body", "דליפת אוויר בין חיישן ה-MAF לגוף המצערת", "medium"),
    ("P2282", "Air Leak Between Throttle Body and Intake Valves", "דליפת אוויר בין גוף המצערת לשסתומי היניקה", "medium"),
])
series("P2283", "Injector Control Pressure Sensor", "חיישן לחץ בקרת המזרקים", S5, "high")
singles([
    ("P2288", "Injector Control Pressure Too High", "לחץ בקרת המזרקים גבוה מדי", "high"),
    ("P2289", "Injector Control Pressure Too High - Engine Off", "לחץ בקרת המזרקים גבוה מדי כשהמנוע כבוי", "high"),
    ("P2290", "Injector Control Pressure Too Low", "לחץ בקרת המזרקים נמוך מדי", "high"),
    ("P2291", "Injector Control Pressure Too Low - Engine Cranking", "לחץ בקרת המזרקים נמוך מדי בזמן ההתנעה", "high"),
    ("P2292", "Injector Control Pressure Erratic", "לחץ בקרת המזרקים לא יציב", "high"),
    ("P2293", "Fuel Pressure Regulator 2 Performance", "בעיית ביצועים בווסת לחץ הדלק 2", "high"),
    ("P2294", "Fuel Pressure Regulator 2 Control Circuit", "וסת לחץ דלק 2 – תקלה במעגל הבקרה", "high"),
    ("P2295", "Fuel Pressure Regulator 2 Control Circuit Low", "וסת לחץ דלק 2 – מתח נמוך במעגל הבקרה", "high"),
    ("P2296", "Fuel Pressure Regulator 2 Control Circuit High", "וסת לחץ דלק 2 – מתח גבוה במעגל הבקרה", "high"),
    ("P2297", "O2 Sensor Out of Range During Deceleration " + bs_en(1, 1), "חיישן O2 מחוץ לטווח בזמן האטה " + bs_he(1, 1), "medium"),
    ("P2298", "O2 Sensor Out of Range During Deceleration " + bs_en(2, 1), "חיישן O2 מחוץ לטווח בזמן האטה " + bs_he(2, 1), "medium"),
    ("P2299", "Brake Pedal Position / Accelerator Pedal Position Incompatible", "לחיצה על הבלם והגז בו-זמנית / אותות דוושות לא תואמים", "medium"),
])

# ==========================================================================
# P23xx – ignition coils, knock per cylinder
# ==========================================================================
for k in range(12):
    L = LETTERS[k]
    n = 2300 + k * 3
    add(cs("P", n), 'Ignition Coil "%s" Primary Control Circuit Low' % L, "סליל הצתה %s (צילינדר %d) – מתח נמוך במעגל הבקרה הראשוני" % (L, k + 1), "high")
    add(cs("P", n + 1), 'Ignition Coil "%s" Primary Control Circuit High' % L, "סליל הצתה %s (צילינדר %d) – מתח גבוה במעגל הבקרה הראשוני" % (L, k + 1), "high")
    add(cs("P", n + 2), 'Ignition Coil "%s" Secondary Circuit' % L, "סליל הצתה %s (צילינדר %d) – תקלה במעגל המשני (מתח גבוה)" % (L, k + 1), "high")
for c in range(1, 13):
    add(cs("P", 2335 + c), "Cylinder %d Above Knock Threshold" % c, "נקישות מעל הסף בצילינדר %d" % c, "high")

# ==========================================================================
# P24xx – EVAP leak pump, secondary air, DPF
# ==========================================================================
series("P2400", "Evaporative Emission System Leak Detection Pump", "משאבת איתור דליפות של EVAP", CO3, "low")
series("P2403", "Evaporative Emission System Leak Detection Pump", "משאבת איתור דליפות של EVAP", ["sc", "src", "sl", "sh", "si"], "low")
series("P2408", "Fuel Cap Sensor/Switch", "חיישן/מתג מכסה הדלק", S5, "low")
singles([
    ("P2413", "Exhaust Gas Recirculation System Performance", "בעיית ביצועים במערכת EGR", "medium"),
    ("P2414", "O2 Sensor Exhaust Sample Error " + bs_en(1, 1), "שגיאה בדגימת גזי הפליטה של חיישן O2 (ייתכן דליפת פליטה) " + bs_he(1, 1), "medium"),
    ("P2415", "O2 Sensor Exhaust Sample Error " + bs_en(2, 1), "שגיאה בדגימת גזי הפליטה של חיישן O2 (ייתכן דליפת פליטה) " + bs_he(2, 1), "medium"),
    ("P2416", "O2 Sensor Signals Swapped Bank 1 Sensor 2 / Bank 1 Sensor 3", "אותות חיישני O2 מוחלפים – בנק 1 חיישן 2 / בנק 1 חיישן 3", "medium"),
    ("P2417", "O2 Sensor Signals Swapped Bank 2 Sensor 2 / Bank 2 Sensor 3", "אותות חיישני O2 מוחלפים – בנק 2 חיישן 2 / בנק 2 חיישן 3", "medium"),
])
series("P2418", "Evaporative Emission System Switching Valve", "שסתום המיתוג של EVAP", CO3, "low")
singles([
    ("P2421", "Evaporative Emission System Vent Valve Stuck Open", "שסתום אוורור EVAP תקוע במצב פתוח", "low"),
    ("P2422", "Evaporative Emission System Vent Valve Stuck Closed", "שסתום אוורור EVAP תקוע במצב סגור", "low"),
    ("P2423", "HC Adsorption Catalyst Efficiency Below Threshold (Bank 1)", "יעילות ממיר ספיחת פחמימנים (HC) נמוכה מהסף (בנק 1)", "medium"),
    ("P2424", "HC Adsorption Catalyst Efficiency Below Threshold (Bank 2)", "יעילות ממיר ספיחת פחמימנים (HC) נמוכה מהסף (בנק 2)", "medium"),
])
series("P2425", "Exhaust Gas Recirculation Cooling Valve", "שסתום קירור EGR", CO3, "medium")
singles([
    ("P2428", "Exhaust Gas Temperature Too High (Bank 1)", "טמפרטורת גזי הפליטה גבוהה מדי (בנק 1)", "high"),
    ("P2429", "Exhaust Gas Temperature Too High (Bank 2)", "טמפרטורת גזי הפליטה גבוהה מדי (בנק 2)", "high"),
])
series("P2430", "Secondary Air Injection System Air Flow/Pressure Sensor {} (Bank 1)", "חיישן זרימה/לחץ של אוויר משני – {} (בנק 1)", S5, "low")
series("P2435", "Secondary Air Injection System Air Flow/Pressure Sensor {} (Bank 2)", "חיישן זרימה/לחץ של אוויר משני – {} (בנק 2)", S5, "low")
singles([
    ("P2440", "Secondary Air Injection System Switching Valve Stuck Open (Bank 1)", "שסתום מיתוג אוויר משני תקוע במצב פתוח (בנק 1)", "low"),
    ("P2441", "Secondary Air Injection System Switching Valve Stuck Closed (Bank 1)", "שסתום מיתוג אוויר משני תקוע במצב סגור (בנק 1)", "low"),
    ("P2442", "Secondary Air Injection System Switching Valve Stuck Open (Bank 2)", "שסתום מיתוג אוויר משני תקוע במצב פתוח (בנק 2)", "low"),
    ("P2443", "Secondary Air Injection System Switching Valve Stuck Closed (Bank 2)", "שסתום מיתוג אוויר משני תקוע במצב סגור (בנק 2)", "low"),
    ("P2444", "Secondary Air Injection System Pump Stuck On (Bank 1)", "משאבת אוויר משני תקועה במצב פועל (בנק 1)", "low"),
    ("P2445", "Secondary Air Injection System Pump Stuck Off (Bank 1)", "משאבת אוויר משני תקועה במצב כבוי (בנק 1)", "low"),
    ("P2446", "Secondary Air Injection System Pump Stuck On (Bank 2)", "משאבת אוויר משני תקועה במצב פועל (בנק 2)", "low"),
    ("P2447", "Secondary Air Injection System Pump Stuck Off (Bank 2)", "משאבת אוויר משני תקועה במצב כבוי (בנק 2)", "low"),
    ("P2450", "Evaporative Emission System Switching Valve Performance/Stuck Open", "שסתום המיתוג של EVAP – בעיית ביצועים / תקוע פתוח", "low"),
    ("P2451", "Evaporative Emission System Switching Valve Stuck Closed", "שסתום המיתוג של EVAP – תקוע סגור", "low"),
])
series("P2452", 'Diesel Particulate Filter Pressure Sensor "A"', "חיישן לחץ מסנן החלקיקים (DPF) A", S5E, "medium")
singles([
    ("P2457", "Exhaust Gas Recirculation Cooling System Performance", "בעיית ביצועים במערכת קירור ה-EGR", "medium"),
    ("P2458", "Diesel Particulate Filter Regeneration Duration", "משך ניקוי (רגנרציה) של מסנן החלקיקים ארוך מדי", "medium"),
    ("P2459", "Diesel Particulate Filter Regeneration Frequency", "ניקוי (רגנרציה) של מסנן החלקיקים תכוף מדי", "medium"),
    ("P2463", "Diesel Particulate Filter Restriction - Soot Accumulation", "מסנן החלקיקים (DPF) סתום – הצטברות פיח", "high"),
    ("P242F", "Diesel Particulate Filter Restriction - Ash Accumulation", "מסנן החלקיקים (DPF) סתום – הצטברות אפר", "high"),
    ("P244A", "Diesel Particulate Filter Differential Pressure Too Low (Bank 1)", "הפרש הלחץ על מסנן החלקיקים נמוך מדי (בנק 1)", "medium"),
    ("P244B", "Diesel Particulate Filter Differential Pressure Too High (Bank 1)", "הפרש הלחץ על מסנן החלקיקים גבוה מדי (בנק 1)", "high"),
    ("P245A", "Exhaust Gas Recirculation Cooler Bypass Control Circuit/Open", "עוקף מצנן ה-EGR – תקלה במעגל הבקרה / מעגל פתוח", "medium"),
    ("P245B", "Exhaust Gas Recirculation Cooler Bypass Control Circuit Range/Performance", "עוקף מצנן ה-EGR – בעיית טווח/ביצועים במעגל הבקרה", "medium"),
    ("P245C", "Exhaust Gas Recirculation Cooler Bypass Control Circuit Low", "עוקף מצנן ה-EGR – מתח נמוך במעגל הבקרה", "medium"),
    ("P245D", "Exhaust Gas Recirculation Cooler Bypass Control Circuit High", "עוקף מצנן ה-EGR – מתח גבוה במעגל הבקרה", "medium"),
])

# ==========================================================================
# P25xx – auxiliary inputs
# ==========================================================================
singles([
    ("P2500", "Generator Lamp/L-Terminal Circuit Low", "אלטרנטור – מתח נמוך במעגל נורית הטעינה / הדק L", "high"),
    ("P2501", "Generator Lamp/L-Terminal Circuit High", "אלטרנטור – מתח גבוה במעגל נורית הטעינה / הדק L", "high"),
    ("P2502", "Charging System Voltage", "מתח מערכת הטעינה לא תקין", "high"),
    ("P2503", "Charging System Voltage Low", "מתח מערכת הטעינה נמוך (המצבר לא נטען)", "high"),
    ("P2504", "Charging System Voltage High", "מתח מערכת הטעינה גבוה", "high"),
])
series("P2505", "ECM/PCM Power Input Signal", "אות הזנת מתח למחשב המנוע", [("", "תקלה"), "rp2", "lo", "hi", "int"], "high")
singles([
    ("P2510", "ECM/PCM Power Relay Sense Circuit Range/Performance", "ממסר הזנה של מחשב המנוע – בעיית טווח/ביצועים במעגל החישה", "high"),
    ("P2511", "ECM/PCM Power Relay Sense Circuit Intermittent", "ממסר הזנה של מחשב המנוע – תקלה לסירוגין במעגל החישה", "high"),
])
series("P2512", "Event Data Recorder Request", "בקשת רשם אירועים (EDR)", ["o", "l", "h"], "low")
series("P2515", 'A/C Refrigerant Pressure Sensor "B"', "חיישן לחץ גז המזגן B", S4, "low")
series("P2519", 'A/C Request "A"', "אות בקשת מזגן A", ["c", "l", "h"], "low")
series("P2522", 'A/C Request "B"', "אות בקשת מזגן B", ["c", "l", "h"], "low")
series("P2525", "Vacuum Reservoir Pressure Sensor", "חיישן לחץ מיכל הוואקום", S5, "medium")
series("P2530", "Ignition Switch Run Position", "מתג ההצתה במצב RUN", ["c", "l", "h"], "medium")
series("P2533", "Ignition Switch Run/Start Position", "מתג ההצתה במצב RUN/START", ["c", "l", "h"], "medium")
series("P2536", "Ignition Switch Accessory Position", "מתג ההצתה במצב ACC", ["c", "l", "h"], "low")
series("P2539", "Low Pressure Fuel System Sensor", "חיישן לחץ דלק במערכת הלחץ הנמוך", S5, "high")
series("P2544", 'Torque Management Request Input Signal "A"', "אות בקשת ניהול מומנט A", [("", "תקלה"), "rp2", "lo", "hi"], "medium")
series("P2548", 'Torque Management Request Input Signal "B"', "אות בקשת ניהול מומנט B", [("", "תקלה"), "rp2", "lo", "hi"], "medium")
series("P2552", "Throttle/Fuel Inhibit", "מעגל חסימת מצערת/דלק", S4, "medium")
series("P2556", "Engine Coolant Level Sensor/Switch", "חיישן/מתג מפלס נוזל קירור", S4, "high")
singles([
    ("P2560", "Engine Coolant Level Low", "מפלס נוזל הקירור נמוך", "stop"),
    ("P2561", "A/C Control Module Requested MIL Illumination", "מודול המזגן ביקש הדלקת נורית תקלה", "low"),
])
series("P2562", 'Turbocharger Boost Control Position Sensor "A"', "חיישן מיקום בקרת לחץ הטורבו A (VGT)", S5, "medium")

# ==========================================================================
# P26xx – computer and output circuits
# ==========================================================================
series("P2600", 'Coolant Pump "A" Control', "משאבת נוזל קירור A (חשמלית)", CO4, "high")
singles([
    ("P2604", 'Intake Air Heater "A" Circuit Range/Performance', "מחמם אוויר יניקה A – בעיית טווח/ביצועים", "low"),
    ("P2605", 'Intake Air Heater "A" Circuit/Open', "מחמם אוויר יניקה A – תקלה במעגל / מעגל פתוח", "low"),
    ("P2606", 'Intake Air Heater "B" Circuit Range/Performance', "מחמם אוויר יניקה B – בעיית טווח/ביצועים", "low"),
    ("P2607", 'Intake Air Heater "B" Circuit Low', "מחמם אוויר יניקה B – מתח נמוך במעגל", "low"),
    ("P2608", 'Intake Air Heater "B" Circuit High', "מחמם אוויר יניקה B – מתח גבוה במעגל", "low"),
    ("P2609", "Intake Air Heater System Performance", "מערכת חימום אוויר היניקה – בעיית ביצועים", "low"),
    ("P2610", "ECM/PCM Internal Engine Off Timer Performance", "שעון כיבוי המנוע הפנימי במחשב המנוע – בעיית ביצועים", "low"),
])
series("P2611", "A/C Refrigerant Distribution Valve", "שסתום חלוקת גז המזגן", CO3, "low")
series("P2614", "Camshaft Position Signal Output", "יציאת אות גל הזיזים",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
series("P2617", "Crankshaft Position Signal Output", "יציאת אות גל הארכובה",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
series("P2620", "Throttle Position Output", "יציאת אות מצב המצערת",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
series("P2623", "Injector Control Pressure Regulator", "וסת לחץ בקרת המזרקים",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "high")
series("P2626", "O2 Sensor Pumping Current Trim {} " + bs_en(1, 1), "כיול זרם השאיבה של חיישן O2 – {} " + bs_he(1, 1), _o2cur, "medium")
series("P2629", "O2 Sensor Pumping Current Trim {} " + bs_en(2, 1), "כיול זרם השאיבה של חיישן O2 – {} " + bs_he(2, 1), _o2cur, "medium")
series("P2632", 'Fuel Pump "B" Control', "משאבת דלק B", CO3, "high")
singles([
    ("P2635", 'Fuel Pump "A" Low Flow/Performance', "משאבת דלק A – ספיקה נמוכה / בעיית ביצועים", "high"),
    ("P2636", 'Fuel Pump "B" Low Flow/Performance', "משאבת דלק B – ספיקה נמוכה / בעיית ביצועים", "high"),
])
series("P2637", 'Torque Management Feedback Signal "A"', "אות משוב ניהול מומנט A", [("", "תקלה"), "rp2", "lo", "hi"], "medium")
series("P2641", 'Torque Management Feedback Signal "B"', "אות משוב ניהול מומנט B", [("", "תקלה"), "rp2", "lo", "hi"], "medium")
_rocker = [("Control Circuit/Open", "תקלה במעגל הבקרה / מעגל פתוח"),
           ("System Performance or Stuck Off", "בעיית ביצועים או תקוע במצב כבוי"),
           ("Stuck On", "תקוע במצב מופעל"),
           ("Control Circuit Low", "מתח נמוך במעגל הבקרה"),
           ("Control Circuit High", "מתח גבוה במעגל הבקרה")]
for base, r, b in ((2645, "A", 1), (2650, "B", 1), (2655, "A", 2), (2660, "B", 2)):
    series(cs("P", base), '"%s" Rocker Arm Actuator {} (Bank %d)' % (r, b), "מפעיל זרועות נדנדה %s (שינוי הרמת שסתומים) – {} (בנק %d)" % (r, b), _rocker, "medium")
series("P2665", 'Fuel Shutoff Valve "B" Control', "שסתום ניתוק דלק B", CO3, "medium")
add("P2668", "Fuel Mode Indicator Lamp Control Circuit", "נורית מצב דלק (בנזין/גז) – תקלה במעגל הבקרה", "low")
series("P2669", 'Actuator Supply Voltage "B"', "מתח הזנה למפעילים B", ["o", "l", "h"], "high")
add("P2672", "Injection Pump Timing Offset", "סטיית תזמון במשאבת ההזרקה", "high")

# ==========================================================================
# P27xx – transmission
# ==========================================================================
for k in range(6):
    add(cs("P", 2700 + k), 'Transmission Friction Element "%s" Apply Time Range/Performance' % LETTERS[k],
        "זמן הפעלה חריג של רכיב חיכוך (מצמד/בלם) %s בתיבה" % LETTERS[k], "medium")
series("P2706", 'Shift Solenoid "F"', "סולנואיד העברת הילוכים F", SOL5, "medium")
singles([
    ("P2711", "Unexpected Mechanical Gear Disengagement", "ניתוק מכני בלתי צפוי של הילוך", "high"),
    ("P2712", "Hydraulic Power Unit Leakage", "דליפה ביחידת הכוח ההידראולית", "high"),
])
for base_s, base_c, L in ((2713, 2718, "D"), (2722, 2727, "E"), (2731, 2736, "F")):
    series(cs("P", base_s), 'Pressure Control Solenoid "%s"' % L, "סולנואיד בקרת לחץ %s בתיבה" % L, SOL5, "medium")
    series(cs("P", base_c), 'Pressure Control Solenoid "%s"' % L, "סולנואיד בקרת לחץ %s בתיבה" % L, CO4, "medium")
series("P2740", 'Transmission Fluid Temperature Sensor "B"', "חיישן טמפרטורת שמן הגיר B", S5, "medium")
series("P2745", 'Intermediate Shaft Speed Sensor "B"', "חיישן מהירות הציר הביניים B בתיבה", SPD4, "medium")
series("P2749", 'Intermediate Shaft Speed Sensor "C"', "חיישן מהירות הציר הביניים C בתיבה", SPD4, "medium")
series("P2753", "Transmission Fluid Cooler Control", "בקרת מצנן שמן הגיר",
       [("Circuit/Open", "תקלה במעגל / מעגל פתוח"), ("Circuit Low", "מתח נמוך במעגל"), ("Circuit High", "מתח גבוה במעגל")], "medium")
series("P2756", "Torque Converter Clutch Pressure Control Solenoid", "סולנואיד בקרת לחץ מצמד ממיר המומנט (TCC)", SOL5, "medium")
series("P2761", "Torque Converter Clutch Pressure Control Solenoid", "סולנואיד בקרת לחץ מצמד ממיר המומנט (TCC)", ["co", "crp", "ch", "cl"], "medium")
series("P2765", 'Input/Turbine Speed Sensor "B"', "חיישן מהירות כניסה/טורבינה B של התיבה", SPD4, "medium")
singles([
    ("P2769", "Torque Converter Clutch Circuit Low", "מצמד ממיר המומנט (TCC) – מתח נמוך במעגל", "medium"),
    ("P2770", "Torque Converter Clutch Circuit High", "מצמד ממיר המומנט (TCC) – מתח גבוה במעגל", "medium"),
])
series("P2771", "Four Wheel Drive (4WD) Low Switch", "מתג הילוך כוח (4LOW)", S4, "medium")
series("P2775", "Upshift Switch", "מתג העלאת הילוך", ["rp", "l", "h", "ie"], "low")
series("P2779", "Downshift Switch", "מתג הורדת הילוך", ["rp", "l", "h", "ie"], "low")
singles([
    ("P2783", "Torque Converter Temperature Too High", "טמפרטורת ממיר המומנט גבוהה מדי", "stop"),
    ("P2784", 'Input/Turbine Speed Sensor "A"/"B" Correlation', "חוסר התאמה בין חיישני מהירות הכניסה A ו-B", "medium"),
    ("P2785", "Clutch Actuator Temperature Too High", "טמפרטורת מפעיל המצמד גבוהה מדי", "high"),
    ("P2786", "Gear Shift Actuator Temperature Too High", "טמפרטורת מפעיל העברת ההילוכים גבוהה מדי", "high"),
    ("P2787", "Clutch Temperature Too High", "טמפרטורת המצמד גבוהה מדי", "stop"),
    ("P2788", "Auto Shift Manual Adaptive Learning at Limit", "הלימוד האדפטיבי של התיבה הרובוטית הגיע לגבול", "medium"),
    ("P2789", "Clutch Adaptive Learning at Limit", "הלימוד האדפטיבי של המצמד הגיע לגבול (מצמד שחוק)", "medium"),
])
series("P2790", "Gate Select Direction", "כיוון בחירת מסלול ההילוכים", ["c", "l", "h"], "medium")
series("P2793", "Gear Shift Direction", "כיוון העברת ההילוכים", ["c", "l", "h"], "medium")
singles([
    ("P2796", "Auxiliary Transmission Fluid Pump Control Circuit", "משאבת שמן גיר עזר – תקלה במעגל הבקרה", "medium"),
    ("P2797", "Auxiliary Transmission Fluid Pump Performance", "משאבת שמן גיר עזר – בעיית ביצועים", "medium"),
])

# ==========================================================================
# P28xx – transmission
# ==========================================================================
series("P2800", 'Transmission Range Sensor "B" {} (PRNDL Input)', "חיישן מצב בורר ההילוכים B (PRNDL) – {}", S5, "medium")
singles([
    ("P2805", 'Transmission Range Sensor "A"/"B" Correlation', "חוסר התאמה בין חיישני מצב הבורר A ו-B", "medium"),
    ("P2806", "Transmission Range Sensor Alignment", "כיוון/יישור חיישן מצב בורר ההילוכים שגוי", "medium"),
])
series("P2807", 'Pressure Control Solenoid "G"', "סולנואיד בקרת לחץ G בתיבה", SOL5, "medium")
series("P2812", 'Pressure Control Solenoid "G"', "סולנואיד בקרת לחץ G בתיבה", CO4, "medium")
series("P2816", 'Shift Solenoid "G"', "סולנואיד העברת הילוכים G", SOL5, "medium")

# ==========================================================================
# P2Axx / P2Bxx / P20xx hex – O2 range, SCR/NOx
# ==========================================================================
for k, (b, s) in enumerate(((1, 1), (1, 2), (1, 3), (2, 1), (2, 2), (2, 3))):
    add("P2A0%d" % k, "O2 Sensor Circuit Range/Performance " + bs_en(b, s), "חיישן O2 – בעיית טווח/ביצועים " + bs_he(b, s), "medium")
singles([
    ("P203F", "Reductant Level Too Low", "מפלס ה-AdBlue נמוך מדי", "high"),
    ("P20B9", 'Reductant Heater "A" Control Circuit/Open', "גוף חימום AdBlue A – תקלה במעגל הבקרה / מעגל פתוח", "medium"),
    ("P20BA", 'Reductant Heater "A" Control Circuit Range/Performance', "גוף חימום AdBlue A – בעיית טווח/ביצועים", "medium"),
    ("P20BB", 'Reductant Heater "A" Control Circuit Low', "גוף חימום AdBlue A – מתח נמוך במעגל הבקרה", "medium"),
    ("P20BC", 'Reductant Heater "A" Control Circuit High', "גוף חימום AdBlue A – מתח גבוה במעגל הבקרה", "medium"),
    ("P20E8", "Reductant Pressure Too Low", "לחץ ה-AdBlue נמוך מדי", "medium"),
    ("P204F", "Reductant System Performance (Bank 1)", "בעיית ביצועים במערכת ה-AdBlue (בנק 1)", "medium"),
    ("P20EE", "SCR NOx Catalyst Efficiency Below Threshold (Bank 1)", "יעילות ממיר ה-SCR להפחתת NOx נמוכה מהסף (בנק 1)", "medium"),
    ("P2BA7", "NOx Exceedance - Empty Reagent Tank", "חריגת NOx – מיכל ה-AdBlue ריק", "high"),
    ("P2BA8", "NOx Exceedance - Interruption of Reagent Dosing", "חריגת NOx – הפסקה במינון ה-AdBlue", "high"),
    ("P2BA9", "NOx Exceedance - Insufficient Reagent Quality", "חריגת NOx – איכות AdBlue לא מספקת", "high"),
    ("P2BAC", "NOx Exceedance - Deactivation of EGR", "חריגת NOx – מערכת ה-EGR הושבתה", "medium"),
    ("P2BAD", "NOx Exceedance - Root Cause Unknown", "חריגת NOx – סיבה לא ידועה", "medium"),
])

# ==========================================================================
# P3400–P3499 – cylinder deactivation / valve control
# ==========================================================================
add("P3400", "Cylinder Deactivation System (Bank 1)", "מערכת השבתת צילינדרים – תקלה (בנק 1)", "medium")
_deact = [
    ("Deactivation/Intake Valve Control Circuit/Open", "השבתה/שסתום יניקה – תקלה במעגל הבקרה / מעגל פתוח"),
    ("Deactivation/Intake Valve Control Performance", "השבתה/שסתום יניקה – בעיית ביצועים"),
    ("Deactivation/Intake Valve Control Circuit Low", "השבתה/שסתום יניקה – מתח נמוך במעגל הבקרה"),
    ("Deactivation/Intake Valve Control Circuit High", "השבתה/שסתום יניקה – מתח גבוה במעגל הבקרה"),
    ("Exhaust Valve Control Circuit/Open", "שסתום פליטה – תקלה במעגל הבקרה / מעגל פתוח"),
    ("Exhaust Valve Control Performance", "שסתום פליטה – בעיית ביצועים"),
    ("Exhaust Valve Control Circuit Low", "שסתום פליטה – מתח נמוך במעגל הבקרה"),
    ("Exhaust Valve Control Circuit High", "שסתום פליטה – מתח גבוה במעגל הבקרה"),
]
for c in range(1, 13):
    for k, (en, he) in enumerate(_deact):
        add(cs("P", 3401 + (c - 1) * 8 + k), "Cylinder %d %s" % (c, en), "צילינדר %d: %s" % (c, he), "medium")
add("P3497", "Cylinder Deactivation System (Bank 2)", "מערכת השבתת צילינדרים – תקלה (בנק 2)", "medium")

# ==========================================================================
# U0xxx – network communication
# ==========================================================================
_busft = [
    ("", "תקלה"), ("Performance", "בעיית ביצועים"),
    ("(+) Open", "קו (+) פתוח"), ("(+) Low", "קו (+) מתח נמוך"), ("(+) High", "קו (+) מתח גבוה"),
    ("(-) Open", "קו (-) פתוח"), ("(-) Low", "קו (-) מתח נמוך"), ("(-) High", "קו (-) מתח גבוה"),
    ("(-) Shorted to Bus (+)", "קצר בין קו (-) לקו (+)"),
]
for base, en, he in ((1, "High Speed CAN Communication Bus", "קו תקשורת CAN מהיר"),
                     (10, "Medium Speed CAN Communication Bus", "קו תקשורת CAN בינוני"),
                     (19, "Low Speed CAN Communication Bus", "קו תקשורת CAN איטי")):
    series(cs("U", base), en, he, _busft, "high")
for k, base in enumerate((28, 36, 44, 52, 60)):
    series(cs("U", base), 'Vehicle Communication Bus "%s"' % LETTERS[k], "קו תקשורת רכב %s" % LETTERS[k], _busft[:8], "high")
for k in range(5):
    add(cs("U", 73 + k), 'Control Module Communication Bus "%s" Off' % LETTERS[k],
        "קו התקשורת %s של מחשבי הרכב כבוי (Bus Off)" % LETTERS[k], "high")

U01_MODULES = [
    (100, 'ECM/PCM "A"', "מחשב המנוע (ECM/PCM)", "high"),
    (101, "Transmission Control Module (TCM)", "מחשב תיבת ההילוכים (TCM)", "high"),
    (102, "Transfer Case Control Module", "מודול תיבת ההעברה (4X4)", "medium"),
    (103, "Gear Shift Module", "מודול ידית ההילוכים", "high"),
    (104, "Cruise Control Module", "מודול בקרת השיוט", "low"),
    (105, "Fuel Injector Control Module", "מודול בקרת המזרקים", "high"),
    (106, "Glow Plug Control Module", "מודול בקרת מצתי הלהט", "medium"),
    (107, "Throttle Actuator Control Module", "מודול בקרת המצערת החשמלית", "high"),
    (108, "Alternative Fuel Control Module", "מודול הדלק החלופי (גז)", "medium"),
    (109, "Fuel Pump Control Module", "מודול בקרת משאבת הדלק", "high"),
    (110, 'Drive Motor Control Module "A"', "מודול בקרת מנוע ההנעה החשמלי A", "high"),
    (111, 'Battery Energy Control Module "A"', "מודול ניהול מצבר ההנעה A (BECM)", "high"),
    (112, 'Battery Energy Control Module "B"', "מודול ניהול מצבר ההנעה B (BECM)", "high"),
    (114, "Four-Wheel Drive Clutch Control Module", "מודול מצמד ההנעה הכפולה", "medium"),
    (115, 'ECM/PCM "B"', "מחשב מנוע B (ECM/PCM)", "high"),
    (117, "Power Take Off Control Module", "מודול בקרת PTO (המראת כוח)", "low"),
    (118, "Fuel Cell Control Module", "מודול בקרת תא הדלק", "high"),
    (119, "Starter/Generator Control Module", "מודול בקרת המתנע/גנרטור", "high"),
    (121, "Anti-Lock Brake System (ABS) Control Module", "מחשב בלמי ABS", "high"),
    (122, "Vehicle Dynamics Control Module", "מודול בקרת יציבות (ESP)", "high"),
    (123, "Yaw Rate Sensor Module", "מודול חיישן הסבסוב (Yaw)", "medium"),
    (124, "Lateral Acceleration Sensor Module", "מודול חיישן תאוצה רוחבית", "medium"),
    (125, "Multi-axis Acceleration Sensor Module", "מודול חיישן תאוצה רב-צירי", "medium"),
    (126, "Steering Angle Sensor Module", "מודול חיישן זווית ההגה", "medium"),
    (127, "Tire Pressure Monitor Module", "מודול ניטור לחץ אוויר בצמיגים (TPMS)", "low"),
    (128, "Park Brake Control Module", "מודול בלם החניה החשמלי", "medium"),
    (129, "Brake System Control Module", "מודול בקרת מערכת הבלמים", "high"),
    (130, "Steering Effort Control Module", "מודול בקרת כוח ההיגוי", "medium"),
    (131, "Power Steering Control Module", "מודול הגה הכוח (EPS)", "high"),
    (132, "Ride Level Control Module", "מודול בקרת גובה המתלים", "low"),
    (140, "Body Control Module", "מודול בקרת המרכב (BCM)", "medium"),
    (141, 'Body Control Module "A"', "מודול בקרת המרכב A", "medium"),
    (142, 'Body Control Module "B"', "מודול בקרת המרכב B", "medium"),
    (143, 'Body Control Module "C"', "מודול בקרת המרכב C", "medium"),
    (144, 'Body Control Module "D"', "מודול בקרת המרכב D", "medium"),
    (145, 'Body Control Module "E"', "מודול בקרת המרכב E", "medium"),
    (146, 'Gateway "A"', "מודול השער (Gateway) A", "high"),
    (151, "Restraints Control Module", "מחשב כריות האוויר (SRS)", "high"),
    (152, "Side Restraints Control Module Left", "מודול כריות אוויר צד שמאל", "high"),
    (153, "Side Restraints Control Module Right", "מודול כריות אוויר צד ימין", "high"),
    (154, "Restraints Occupant Classification System Module", "מודול זיהוי נוסע (כרית אוויר נוסע)", "high"),
    (155, "Instrument Panel Cluster (IPC) Control Module", "לוח המחוונים (IPC)", "medium"),
    (156, 'Information Center "A"', "מרכז המידע A", "low"),
    (157, 'Information Center "B"', "מרכז המידע B", "low"),
    (158, "Head Up Display", "תצוגה עילית (HUD)", "low"),
    (159, 'Parking Assist Control Module "A"', "מודול חיישני חניה A", "low"),
    (160, "Audible Alert Control Module", "מודול ההתראות הקוליות", "low"),
    (161, "Compass Module", "מודול המצפן", "low"),
    (162, "Navigation Display Module", "מסך הניווט", "low"),
    (163, "Navigation Control Module", "מודול הניווט", "low"),
    (164, "HVAC Control Module", "מודול בקרת המיזוג (HVAC)", "low"),
    (165, "HVAC Control Module Rear", "מודול בקרת המיזוג האחורי", "low"),
    (166, "Auxiliary Heater Control Module", "מודול מחמם העזר", "low"),
    (167, "Vehicle Immobilizer Control Module", "מודול האימובילייזר", "high"),
    (168, "Vehicle Security Control Module", "מודול מערכת האבטחה", "medium"),
    (169, "Sunroof Control Module", "מודול חלון הגג", "low"),
    (170, 'Restraints System Sensor "A"', "חיישן מערכת כריות האוויר A", "high"),
    (171, 'Restraints System Sensor "B"', "חיישן מערכת כריות האוויר B", "high"),
    (172, 'Restraints System Sensor "C"', "חיישן מערכת כריות האוויר C", "high"),
    (173, 'Restraints System Sensor "D"', "חיישן מערכת כריות האוויר D", "high"),
    (174, 'Restraints System Sensor "E"', "חיישן מערכת כריות האוויר E", "high"),
    (184, "Radio", "הרדיו / מערכת השמע", "low"),
    (185, "Antenna Control Module", "מודול האנטנה", "low"),
    (186, "Audio Amplifier", "מגבר השמע", "low"),
    (191, "Television", "מודול הטלוויזיה", "low"),
    (195, "Subscription Entertainment Receiver Module", "מקלט הבידור (רדיו לווייני)", "low"),
    (197, "Telephone Control Module", "מודול הטלפון / דיבורית", "low"),
    (198, "Telematic Control Module", "מודול הטלמטיקה", "low"),
    (199, 'Door Control Module "A"', "מודול דלת A", "low"),
    (200, 'Door Control Module "B"', "מודול דלת B", "low"),
    (201, 'Door Control Module "C"', "מודול דלת C", "low"),
    (202, 'Door Control Module "D"', "מודול דלת D", "low"),
    (203, 'Door Control Module "E"', "מודול דלת E", "low"),
    (204, 'Door Control Module "F"', "מודול דלת F", "low"),
    (205, 'Door Control Module "G"', "מודול דלת G", "low"),
    (206, "Folding Top Control Module", "מודול הגג המתקפל", "low"),
    (207, "Moveable Roof Control Module", "מודול הגג הנע", "low"),
    (208, 'Seat Control Module "A"', "מודול מושב A", "low"),
    (209, 'Seat Control Module "B"', "מודול מושב B", "low"),
    (210, 'Seat Control Module "C"', "מודול מושב C", "low"),
    (211, 'Seat Control Module "D"', "מודול מושב D", "low"),
    (212, "Steering Column Control Module", "מודול עמוד ההגה", "medium"),
    (213, "Mirror Control Module", "מודול המראות", "low"),
    (214, "Remote Function Actuation", "מודול השלט / כניסה ללא מפתח", "low"),
    (230, "Rear Gate Module", "מודול דלת תא המטען", "low"),
    (231, "Rain Sensing Module", "חיישן הגשם", "low"),
    (232, "Side Obstacle Detection Control Module Left", "מודול זיהוי שטח מת שמאלי", "low"),
    (233, "Side Obstacle Detection Control Module Right", "מודול זיהוי שטח מת ימני", "low"),
    (235, "Cruise Control Front Distance Range Sensor", "חיישן המרחק הקדמי (רדאר בקרת שיוט אדפטיבית)", "medium"),
]
for n, en, he, sv in U01_MODULES:
    add(cs("U", n), "Lost Communication With %s" % en, "אין תקשורת עם %s" % he, sv)

U34_MODULES = [
    ('ECM/PCM "A"', "מחשב המנוע (ECM/PCM)"),
    ("TCM", "מחשב תיבת ההילוכים (TCM)"),
    ("Transfer Case Control Module", "מודול תיבת ההעברה"),
    ("Gear Shift Control Module", "מודול ידית ההילוכים"),
    ("Cruise Control Module", "מודול בקרת השיוט"),
    ("Fuel Injector Control Module", "מודול בקרת המזרקים"),
    ("Glow Plug Control Module", "מודול בקרת מצתי הלהט"),
    ("Throttle Actuator Control Module", "מודול בקרת המצערת"),
    ("Alternative Fuel Control Module", "מודול הדלק החלופי (גז)"),
    ("Fuel Pump Control Module", "מודול בקרת משאבת הדלק"),
    ("Drive Motor Control Module", "מודול בקרת מנוע ההנעה החשמלי"),
    ('Battery Energy Control Module "A"', "מודול ניהול מצבר ההנעה A"),
    ('Battery Energy Control Module "B"', "מודול ניהול מצבר ההנעה B"),
    ("Four-Wheel Drive Clutch Control Module", "מודול מצמד ההנעה הכפולה"),
    ("Anti-Lock Brake System Control Module", "מחשב בלמי ABS"),
    ("Vehicle Dynamics Control Module", "מודול בקרת היציבות (ESP)"),
    ("Park Brake Control Module", "מודול בלם החניה"),
    ("Brake System Control Module", "מודול בקרת הבלמים"),
    ("Steering Effort Control Module", "מודול בקרת כוח ההיגוי"),
    ("Power Steering Control Module", "מודול הגה הכוח"),
    ("Ride Level Control Module", "מודול בקרת גובה המתלים"),
    ("Body Control Module", "מודול בקרת המרכב (BCM)"),
    ("Instrument Panel Control Module", "לוח המחוונים"),
    ("HVAC Control Module", "מודול המיזוג"),
    ("Auxiliary Heater Control Module", "מודול מחמם העזר"),
    ("Vehicle Immobilizer Control Module", "מודול האימובילייזר"),
    ("Vehicle Security Control Module", "מודול האבטחה"),
    ("Steering Angle Sensor Module", "מודול חיישן זווית ההגה"),
    ("Steering Column Control Module", "מודול עמוד ההגה"),
    ("Tire Pressure Monitor Module", "מודול ניטור לחץ הצמיגים"),
    ('Body Control Module "A"', "מודול בקרת המרכב A"),
]
add("U0300", "Internal Control Module Software Incompatibility", "חוסר תאימות תוכנה בין מחשבי הרכב", "medium")
for k, (en, he) in enumerate(U34_MODULES):
    add(cs("U", 301 + k), "Software Incompatibility With %s" % en, "חוסר תאימות תוכנה עם %s" % he, "medium")
    add(cs("U", 401 + k), "Invalid Data Received From %s" % en, "התקבלו נתונים לא תקינים מ%s" % he, "medium")

# ==========================================================================
# C0xxx – chassis (commonly used generic definitions)
# ==========================================================================
WHEELS = [("Left Front", "גלגל קדמי שמאלי"), ("Right Front", "גלגל קדמי ימני"),
          ("Left Rear", "גלגל אחורי שמאלי"), ("Right Rear", "גלגל אחורי ימני")]
for k, (w, w_he) in enumerate(WHEELS):
    n = 35 + 5 * k
    add(cs("C", n), "%s Wheel Speed Sensor Circuit" % w, "חיישן מהירות %s (ABS) – תקלה במעגל" % w_he, "medium")
    add(cs("C", n + 1), "%s Wheel Speed Sensor Circuit Range/Performance" % w,
        "חיישן מהירות %s (ABS) – בעיית טווח/ביצועים" % w_he, "medium")
    add(cs("C", 60 + 10 * k), "%s ABS Solenoid #1 Circuit" % w, "סולנואיד ABS מס' 1 של %s – תקלה במעגל" % w_he, "high")
    add(cs("C", 65 + 10 * k), "%s ABS Solenoid #2 Circuit" % w, "סולנואיד ABS מס' 2 של %s – תקלה במעגל" % w_he, "high")
singles([
    ("C0110", "ABS Pump Motor Circuit", "מנוע משאבת ה-ABS – תקלה במעגל", "high"),
    ("C0121", "ABS Valve Relay Circuit", "ממסר שסתומי ה-ABS – תקלה במעגל", "high"),
    ("C0131", "ABS/TCS System Pressure Circuit", "מעגל לחץ מערכת ABS/בקרת משיכה – תקלה", "high"),
    ("C0161", "ABS/TCS Brake Switch Circuit", "מתג הבלם של מערכת ABS/בקרת משיכה – תקלה במעגל", "medium"),
    ("C0186", "Lateral Accelerometer Circuit", "חיישן תאוצה רוחבית – תקלה במעגל", "medium"),
    ("C0196", "Yaw Rate Sensor Circuit", "חיישן סבסוב (Yaw) – תקלה במעגל", "medium"),
    ("C0245", "Wheel Speed Sensor Frequency Error", "שגיאת תדר בחיישן מהירות גלגל", "medium"),
    ("C0265", "EBCM Relay Circuit", "ממסר מחשב הבלמים (EBCM) – תקלה במעגל", "high"),
    ("C0267", "ABS Pump Motor Circuit Open/Shorted", "מנוע משאבת ה-ABS – מעגל פתוח או קצר", "high"),
    ("C0281", "Brake Switch Circuit", "מתג הבלם – תקלה במעגל", "medium"),
    ("C0455", "Steering Wheel Position Sensor Circuit", "חיישן מיקום ההגה – תקלה במעגל", "medium"),
    ("C0550", "Electronic Control Unit Performance", "בעיית ביצועים במחשב הבלמים/היציבות", "high"),
    ("C0561", "System Disabled Information Stored", "מערכת ה-ABS/ESP הושבתה (מידע נשמר)", "medium"),
    ("C0710", "Steering Position Signal", "אות מיקום ההגה – תקלה", "medium"),
])

# ==========================================================================
# B0xxx – body / restraints (commonly used generic definitions)
# ==========================================================================
singles([
    ("B0001", "Driver Frontal Stage 1 Deployment Control", "כרית אוויר קדמית נהג, שלב 1 – תקלה במעגל ההפעלה", "high"),
    ("B0002", "Driver Frontal Stage 2 Deployment Control", "כרית אוויר קדמית נהג, שלב 2 – תקלה במעגל ההפעלה", "high"),
    ("B0003", "Driver Frontal Stage 3 Deployment Control", "כרית אוויר קדמית נהג, שלב 3 – תקלה במעגל ההפעלה", "high"),
    ("B0010", "Passenger Frontal Stage 1 Deployment Control", "כרית אוויר קדמית נוסע, שלב 1 – תקלה במעגל ההפעלה", "high"),
    ("B0011", "Passenger Frontal Stage 2 Deployment Control", "כרית אוויר קדמית נוסע, שלב 2 – תקלה במעגל ההפעלה", "high"),
    ("B0012", "Passenger Frontal Stage 3 Deployment Control", "כרית אוויר קדמית נוסע, שלב 3 – תקלה במעגל ההפעלה", "high"),
    ("B0020", "Left Side Airbag Deployment Control", "כרית אוויר צד שמאל – תקלה במעגל ההפעלה", "high"),
    ("B0028", "Right Side Airbag Deployment Control", "כרית אוויר צד ימין – תקלה במעגל ההפעלה", "high"),
    ("B0050", "Driver Seatbelt Sensor", "חיישן חגורת בטיחות נהג – תקלה", "medium"),
    ("B0052", "Passenger Seatbelt Sensor", "חיישן חגורת בטיחות נוסע – תקלה", "medium"),
    ("B0100", "Electronic Frontal Sensor 1", "חיישן התנגשות קדמי 1 – תקלה", "high"),
    ("B0103", "Electronic Frontal Sensor 2", "חיישן התנגשות קדמי 2 – תקלה", "high"),
])

# ==========================================================================
# Groups – fallback descriptions by 3-character prefix
# ==========================================================================
HEX = "0123456789ABCDEF"
_g_fuel = ("Fuel and air metering and auxiliary emission controls", "מינון דלק ואוויר ובקרות פליטה משניות", "medium")
_g_hyb = ("Hybrid / electric propulsion", "מערכת הנעה היברידית / חשמלית", "high")
_g_trans = ("Transmission", "תיבת הילוכים", "medium")
GROUP_DEFS = {
    "P00": _g_fuel,
    "P01": ("Fuel and air metering", "מינון דלק ואוויר (חיישנים ותערובת)", "medium"),
    "P02": ("Fuel and air metering (injector circuit)", "מינון דלק ואוויר (מזרקים, משאבת דלק, טורבו)", "medium"),
    "P03": ("Ignition system or misfire", "מערכת הצתה או תקלות הצתה", "high"),
    "P04": ("Auxiliary emission controls", "בקרות פליטה משניות (EGR, ממיר קטליטי, EVAP)", "medium"),
    "P05": ("Vehicle speed controls, idle control system and auxiliary inputs", "בקרת מהירות, בקרת סרק וכניסות עזר", "medium"),
    "P06": ("Computer and auxiliary outputs", "מחשב המנוע ומעגלי יציאה", "high"),
    "P07": _g_trans, "P08": _g_trans, "P09": _g_trans,
    "P20": _g_fuel, "P21": _g_fuel, "P22": _g_fuel,
    "P23": ("Ignition system or misfire", "מערכת הצתה או תקלות הצתה", "high"),
    "P24": ("Auxiliary emission controls", "בקרות פליטה משניות (EVAP, אוויר משני, DPF)", "medium"),
    "P25": ("Auxiliary inputs", "כניסות עזר (טעינה, מתג הצתה, חיישנים נלווים)", "medium"),
    "P26": ("Computer and auxiliary outputs", "מחשב המנוע ומעגלי יציאה", "high"),
    "P27": _g_trans, "P28": _g_trans,
    "P29": _g_fuel,
    "P2A": _g_fuel, "P2B": _g_fuel, "P2C": _g_fuel, "P2D": _g_fuel, "P2E": _g_fuel, "P2F": _g_fuel,
    "P34": ("Cylinder deactivation / valve control", "השבתת צילינדרים / בקרת שסתומים", "medium"),
    "U00": ("Network electrical (communication bus)", "חשמל רשת התקשורת (קווי CAN)", "high"),
    "U01": ("Network communication – lost communication with a module", "רשת תקשורת – אין תקשורת עם מודול", "high"),
    "U02": ("Network communication – lost communication with a module", "רשת תקשורת – אין תקשורת עם מודול", "medium"),
    "U03": ("Network communication – software incompatibility", "רשת תקשורת – חוסר תאימות תוכנה בין מודולים", "medium"),
    "U04": ("Network communication – invalid data received", "רשת תקשורת – התקבלו נתונים לא תקינים", "medium"),
    "U05": ("Network communication – invalid data received", "רשת תקשורת – התקבלו נתונים לא תקינים", "medium"),
}
for h in "ABCDEF":
    GROUP_DEFS["P0" + h] = _g_hyb
for h in HEX:
    GROUP_DEFS.setdefault("C0" + h, ("Chassis system (brakes/ABS, stability, steering, suspension)",
                                     "מערכות שלדה (בלמים/ABS, יציבות, היגוי, מתלים)", "medium"))
    GROUP_DEFS.setdefault("B0" + h, ("Body system (airbags/restraints, seat belts, comfort and lighting)",
                                     "מערכות מרכב (כריות אוויר, חגורות, נוחות ותאורה)", "medium"))
    GROUP_DEFS.setdefault("U0" + h, ("Network communication", "רשת התקשורת בין מחשבי הרכב", "medium"))
GROUP_DEFS["B00"] = ("Body – restraints / airbag deployment", "מרכב – כריות אוויר ומערכות ריסון", "high")
GROUP_DEFS["B01"] = ("Body – restraints / crash sensors", "מרכב – כריות אוויר וחיישני התנגשות", "high")
GROUP_DEFS["C00"] = ("Chassis – brakes / ABS / wheel speed sensors", "שלדה – בלמים, ABS וחיישני מהירות גלגלים", "medium")
GROUP_DEFS["C01"] = ("Chassis – ABS / traction / stability control", "שלדה – ABS, בקרת משיכה ויציבות", "medium")

GROUP_ORDER = (["P0" + h for h in HEX] + ["P2" + h for h in HEX] + ["P34"]
               + ["C0" + h for h in HEX] + ["B0" + h for h in HEX] + ["U0" + h for h in HEX])
GROUPS = []
for p in GROUP_ORDER:
    en, he, sv = GROUP_DEFS[p]
    GROUPS.append({"prefix": p, "en": en, "he": he, "sev": sv})

# ==========================================================================
# Rich details: severity, likely causes, practical tip
# ==========================================================================
RICH = {}


def R(codes, sev, causes, tip_en, tip_he):
    if isinstance(codes, str):
        codes = [codes]
    for code in codes:
        if code in RICH:
            raise SystemExit("duplicate rich entry: %s" % code)
        RICH[code] = (sev, causes, tip_en, tip_he)


# ---- common cause fragments ------------------------------------------------
C_WIRING = ("Damaged wiring or loose/corroded connector", "חיווט פגוע או מחבר רופף/מחומצן")
C_ECM = ("ECM/PCM fault (rare)", "תקלה במחשב המנוע (נדיר)")
C_VAC = ("Vacuum leak (cracked hose, intake gasket)", "דליפת ואקום (צינור סדוק, אטם סעפת)")
C_EXLEAK = ("Exhaust leak upstream of the sensor", "דליפת פליטה לפני החיישן")
C_OIL = ("Low or dirty engine oil", "שמן מנוע נמוך או מלוכלך")
C_FUSE = ("Blown fuse or faulty relay", "נתיך שרוף או ממסר תקול")
C_BATT = ("Weak battery or poor ground connection", "מצבר חלש או הארקה לא טובה")
C_CAP = ("Loose, damaged or wrong fuel cap", "מכסה דלק רופף, פגום או לא מקורי")

# ---- variable valve timing P0010-P0025 -------------------------------------
VVT_CIRC = [("Faulty oil control valve (VVT solenoid)", "שסתום בקרת שמן (סולנואיד VVT) תקול"), C_WIRING,
            C_FUSE, C_ECM]
VVT_TIME = [C_OIL, ("Sticking oil control valve (VVT solenoid)", "שסתום בקרת שמן (VVT) נתקע"),
            ("Worn VVT phaser/actuator", "מפעיל VVT (פאזר) שחוק"),
            ("Stretched timing chain or jumped tooth", "שרשרת תזמון מתוחה או שקפצה שן")]
VVT_TIP_EN = "Check the oil level and condition first and change it if old; then test the VVT solenoid. A rattle on cold start points to the timing chain."
VVT_TIP_HE = "בדקו קודם את מפלס ומצב השמן והחליפו אם הוא ישן, ואז בדקו את סולנואיד ה-VVT. רעש שקשוק בהתנעה קרה מרמז על שרשרת התזמון."
R(["P0010", "P0013", "P0020", "P0023"], "medium", VVT_CIRC,
  "Inspect the VVT solenoid connector and wiring and measure the solenoid resistance; a failed solenoid is a common fix.",
  "בדקו את המחבר והחיווט של סולנואיד ה-VVT ומדדו את ההתנגדות שלו; החלפת סולנואיד היא תיקון נפוץ.")
R(["P0011", "P0012", "P0014", "P0015", "P0021", "P0022", "P0024", "P0025"], "medium", VVT_TIME, VVT_TIP_EN, VVT_TIP_HE)
R(["P0016", "P0017", "P0018", "P0019"], "high",
  [("Stretched timing chain or belt", "שרשרת/רצועת תזמון מתוחה"), ("Timing jumped a tooth", "התזמון קפץ שן"),
   ("Faulty crankshaft or camshaft position sensor", "חיישן גל ארכובה או גל זיזים תקול"),
   ("Damaged sensor reluctor/tone wheel", "גלגל שיניים של החיישן פגום"), C_OIL],
  "Do not ignore this code: a stretched or jumped timing chain can damage the engine. Have the timing marks checked.",
  "אל תזניחו את הקוד: שרשרת תזמון מתוחה או שקפצה עלולה לגרום נזק למנוע. בקשו לבדוק את סימני התזמון.")

# ---- fuel pressure ------------------------------------------------------------
R("P0087", "high",
  [("Clogged fuel filter", "מסנן דלק סתום"), ("Weak or failing fuel pump", "משאבת דלק חלשה או כושלת"),
   ("Faulty fuel rail pressure sensor", "חיישן לחץ מסילת הדלק תקול"),
   ("Faulty pressure regulator / high-pressure pump", "וסת לחץ או משאבת לחץ גבוה תקולים"),
   ("Very low fuel level or air in the system (diesel)", "מפלס דלק נמוך מאוד או אוויר במערכת (דיזל)")],
  "Refuel and check/replace the fuel filter first. Low rail pressure can cause stalling and limp mode, so avoid heavy loads until fixed.",
  "תדלקו ובדקו/החליפו קודם את מסנן הדלק. לחץ נמוך במסילה עלול לגרום לכיבוי המנוע ולמצב חירום, לכן הימנעו מעומס עד לתיקון.")
R("P0089", "high",
  [("Faulty fuel pressure regulator", "וסת לחץ דלק תקול"), ("Faulty rail pressure sensor", "חיישן לחץ מסילה תקול"),
   ("Weak high-pressure pump", "משאבת לחץ גבוה חלשה"), C_WIRING],
  "Compare actual vs. desired rail pressure in live data; a regulator that cannot follow the target is usually the culprit.",
  "השוו בנתונים חיים בין לחץ המסילה בפועל ללחץ הרצוי; וסת שלא עומד ביעד הוא בדרך כלל האשם.")
R("P0191", "high",
  [("Faulty fuel rail pressure sensor", "חיישן לחץ מסילת הדלק תקול"), C_WIRING,
   ("Weak fuel pump or clogged filter", "משאבת דלק חלשה או מסנן סתום"), ("Faulty pressure regulator", "וסת לחץ תקול")],
  "Check the sensor connector and compare the reading with a mechanical fuel pressure gauge.",
  "בדקו את מחבר החיישן והשוו את הקריאה למד לחץ דלק מכני.")

# ---- MAF / MAP / IAT / ECT / TPS -------------------------------------------
C_MAF_DIRTY = ("Dirty or contaminated MAF sensor", "חיישן MAF מלוכלך")
R("P0101", "medium",
  [C_MAF_DIRTY, ("Air leak between MAF and throttle body", "דליפת אוויר בין ה-MAF למצערת"),
   ("Clogged air filter", "מסנן אוויר סתום"), ("Over-oiled aftermarket air filter", "מסנן אוויר ספורט עם עודף שמן"),
   ("Faulty MAF sensor", "חיישן MAF תקול")],
  "Inspect the air filter and intake hose for cracks, then clean the MAF with dedicated MAF cleaner spray (never touch the sensing wire).",
  "בדקו את מסנן האוויר ואת צינור היניקה לסדקים, ואז נקו את ה-MAF בתרסיס ייעודי (אל תגעו בחוט החיישן).")
R("P0102", "medium",
  [("MAF connector unplugged or loose", "מחבר ה-MAF מנותק או רופף"), ("Open or shorted-to-ground wiring", "חוט קטוע או בקצר לאדמה"),
   C_MAF_DIRTY, ("Faulty MAF sensor", "חיישן MAF תקול")],
  "Check that the MAF connector is fully seated and inspect the wiring; then clean or replace the sensor.",
  "ודאו שמחבר ה-MAF מחובר היטב ובדקו את החיווט; לאחר מכן נקו או החליפו את החיישן.")
R("P0103", "medium",
  [("Wiring shorted to voltage", "חוט בקצר למתח"), ("Faulty MAF sensor", "חיישן MAF תקול"),
   ("Poor sensor ground", "הארקת חיישן לקויה"), C_ECM],
  "Inspect the MAF wiring for chafing against hot or moving parts; a failed sensor is the most common cause.",
  "בדקו שחיווט ה-MAF לא שפשף בחלקים חמים או נעים; חיישן תקול הוא הסיבה הנפוצה ביותר.")
R("P0106", "medium",
  [C_VAC, ("Cracked or disconnected MAP hose", "צינור ה-MAP סדוק או מנותק"), ("Faulty MAP sensor", "חיישן MAP תקול"),
   ("Engine mechanical problem (low vacuum)", "בעיה מכנית במנוע (ואקום נמוך)")],
  "Check the small vacuum hose to the MAP sensor and listen for hissing vacuum leaks at idle.",
  "בדקו את צינור הוואקום הקטן של חיישן ה-MAP והקשיבו לשריקה של דליפת ואקום בסרק.")
R("P0107", "medium",
  [("MAP sensor disconnected", "חיישן MAP מנותק"), ("Signal wire shorted to ground", "חוט האות בקצר לאדמה"),
   ("5V reference problem", "בעיה במתח הייחוס 5V"), ("Faulty MAP sensor", "חיישן MAP תקול")],
  "Check the MAP connector and the 5V reference at the sensor before replacing it.",
  "בדקו את מחבר ה-MAP ואת מתח הייחוס 5V בחיישן לפני החלפה.")
R("P0108", "medium",
  [("Signal wire shorted to voltage", "חוט האות בקצר למתח"), ("Vacuum hose to sensor disconnected", "צינור ואקום לחיישן מנותק"),
   ("Faulty MAP sensor", "חיישן MAP תקול"), ("Poor sensor ground", "הארקת חיישן לקויה")],
  "With the engine off the MAP should read close to atmospheric pressure; a reading near maximum with the engine running points to the sensor or wiring.",
  "כשהמנוע כבוי חיישן ה-MAP אמור להראות לחץ אטמוספרי; קריאה מקסימלית כשהמנוע פועל מצביעה על החיישן או החיווט.")
R("P0113", "medium",
  [("IAT sensor unplugged", "חיישן IAT מנותק"), ("Open wiring", "חוט קטוע"), ("Faulty IAT sensor", "חיישן IAT תקול"), C_WIRING],
  "If the scanner shows about -40°C intake air temperature, the circuit is open: check the connector (often built into the MAF).",
  "אם הסורק מראה כ-40- מעלות בטמפרטורת אוויר היניקה, המעגל פתוח: בדקו את המחבר (לרוב משולב בחיישן ה-MAF).")
R("P0112", "medium",
  [("IAT signal shorted to ground", "אות ה-IAT בקצר לאדמה"), ("Faulty IAT sensor", "חיישן IAT תקול"), C_WIRING],
  "A very high IAT reading on a cold engine means a short; inspect the wiring before replacing the sensor.",
  "קריאת IAT גבוהה מאוד במנוע קר מעידה על קצר; בדקו את החיווט לפני החלפת החיישן.")
R("P0116", "medium",
  [("Faulty coolant temperature sensor", "חיישן טמפרטורת נוזל קירור תקול"), ("Thermostat stuck open", "תרמוסטט תקוע פתוח"),
   ("Low coolant or air in the system", "נוזל קירור נמוך או אוויר במערכת"), C_WIRING],
  "Check the coolant level when the engine is cold and compare the ECT reading with the actual engine temperature.",
  "בדקו את מפלס נוזל הקירור כשהמנוע קר והשוו את קריאת החיישן לטמפרטורה האמיתית של המנוע.")
R("P0117", "medium",
  [("ECT signal shorted to ground", "אות חיישן הטמפרטורה בקצר לאדמה"), ("Faulty ECT sensor", "חיישן טמפרטורת נוזל קירור תקול"), C_WIRING],
  "The gauge or fan may behave wrongly; inspect the sensor connector and replace the sensor if the reading is stuck high.",
  "מד החום או המאוורר עלולים לפעול לא תקין; בדקו את מחבר החיישן והחליפו אותו אם הקריאה תקועה גבוה.")
R("P0118", "medium",
  [("ECT sensor unplugged or open circuit", "חיישן הטמפרטורה מנותק או מעגל פתוח"), ("Faulty ECT sensor", "חיישן טמפרטורת נוזל קירור תקול"),
   ("Corroded connector", "מחבר מחומצן")],
  "Watch the temperature gauge: the engine may actually overheat while the computer sees 'cold'. Fix promptly.",
  "שימו עין על מד החום: המנוע עלול להתחמם בפועל בזמן שהמחשב 'רואה' מנוע קר. תקנו בהקדם.")
R("P0121", "high",
  [("Worn throttle position sensor", "חיישן מצב מצערת שחוק"), ("Dirty throttle body", "גוף מצערת מלוכלך"), C_WIRING,
   ("Faulty electronic throttle body", "גוף מצערת חשמלי תקול")],
  "Clean the throttle body and check the TPS connector; the car may enter limp mode until repaired.",
  "נקו את גוף המצערת ובדקו את מחבר חיישן המצערת; הרכב עלול להיכנס למצב חירום עד לתיקון.")
R("P0122", "high",
  [("TPS signal open or shorted to ground", "אות חיישן המצערת קטוע או בקצר לאדמה"), ("Faulty TPS", "חיישן מצערת תקול"),
   ("5V reference problem", "בעיה במתח הייחוס 5V")],
  "Inspect the throttle sensor connector and wiring; expect reduced power until the fault is fixed.",
  "בדקו את מחבר וחיווט חיישן המצערת; צפו להגבלת כוח עד לתיקון.")
R("P0123", "high",
  [("TPS signal shorted to voltage", "אות חיישן המצערת בקצר למתח"), ("Faulty TPS", "חיישן מצערת תקול"), ("Poor sensor ground", "הארקת חיישן לקויה")],
  "Check the sensor ground and wiring for shorts to 5V/12V before replacing the throttle body.",
  "בדקו את ההארקה והחיווט לקצר ל-5V/12V לפני החלפת גוף המצערת.")
R("P0125", "medium",
  [("Thermostat stuck open", "תרמוסטט תקוע פתוח"), ("Faulty coolant temperature sensor", "חיישן טמפרטורת נוזל קירור תקול"),
   ("Low coolant level", "מפלס נוזל קירור נמוך")],
  "If the temperature gauge rises slowly or the heater blows lukewarm air, replace the thermostat.",
  "אם מד החום עולה לאט או שהחימום בתא הנוסעים פושר, החליפו את התרמוסטט.")
R("P0128", "medium",
  [("Thermostat stuck open", "תרמוסטט תקוע פתוח"), ("Faulty coolant temperature sensor", "חיישן טמפרטורת נוזל קירור תקול"),
   ("Low coolant level", "מפלס נוזל קירור נמוך"), ("Cooling fan running constantly", "מאוורר הקירור פועל כל הזמן")],
  "Almost always a thermostat stuck open. It is safe to drive, but fuel consumption and heater output suffer.",
  "כמעט תמיד מדובר בתרמוסטט שנתקע פתוח. אפשר להמשיך לנסוע, אבל צריכת הדלק והחימום נפגעים.")

# ---- O2 sensors ---------------------------------------------------------------
C_O2 = ("Faulty oxygen sensor", "חיישן חמצן (O2) תקול")
O2_RICH = {
    0: ([C_O2, C_WIRING, C_EXLEAK, C_ECM],
        "Inspect the sensor wiring near the exhaust for melted or chafed insulation, then check the sensor signal in live data.",
        "בדקו את חיווט החיישן ליד מערכת הפליטה (בידוד מותך או משופשף), ואז בדקו את אות החיישן בנתונים חיים."),
    1: ([C_EXLEAK, C_VAC, C_O2, ("Signal wire shorted to ground", "חוט האות בקצר לאדמה")],
        "A constantly low voltage often means a real lean condition or an exhaust leak; check for leaks before replacing the sensor.",
        "מתח נמוך קבוע מעיד לרוב על תערובת דלה אמיתית או דליפת פליטה; בדקו דליפות לפני החלפת החיישן."),
    2: ([("Rich mixture (leaking injector, high fuel pressure)", "תערובת עשירה (מזרק דולף, לחץ דלק גבוה)"),
         ("Contaminated sensor (oil, coolant)", "חיישן מזוהם (שמן, נוזל קירור)"), C_O2,
         ("Signal wire shorted to voltage", "חוט האות בקצר למתח")],
        "Check for a rich condition (fuel smell, black smoke) and wiring shorts before replacing the sensor.",
        "בדקו אם יש תערובת עשירה (ריח דלק, עשן שחור) וקצרים בחיווט לפני החלפת החיישן."),
    3: ([("Aged / worn oxygen sensor", "חיישן חמצן ישן/שחוק"), ("Sensor contaminated by oil, coolant or silicone", "חיישן מזוהם משמן, נוזל קירור או סיליקון"),
         C_EXLEAK, C_VAC],
        "A slow sensor is usually worn out; sensors typically last 100,000–160,000 km.",
        "חיישן איטי הוא בדרך כלל חיישן שחוק; אורך חיי חיישן הוא בדרך כלל 100,000–160,000 ק\"מ."),
    4: ([("Dead oxygen sensor", "חיישן חמצן מת"), ("Heater circuit failure", "תקלה בגוף החימום של החיישן"),
         ("Open signal wiring", "חוט אות קטוע"), C_EXLEAK],
        "Check whether the sensor heater works and the connector is intact; a sensor with no activity usually needs replacement.",
        "בדקו שגוף החימום של החיישן עובד ושהמחבר תקין; חיישן ללא פעילות דורש בדרך כלל החלפה."),
    5: ([("Burned-out sensor heater element", "גוף החימום בחיישן נשרף"), ("Blown O2 heater fuse", "נתיך חימום חיישן O2 שרוף"),
         C_WIRING, C_ECM],
        "Check the O2 heater fuse first, then measure the heater resistance (usually a few ohms). Most often the sensor must be replaced.",
        "בדקו קודם את נתיך חימום חיישני ה-O2, ואז מדדו את התנגדות גוף החימום (בדרך כלל כמה אוהם). לרוב יש להחליף את החיישן."),
}
for base in (130, 136, 150, 156):
    for off in range(6):
        causes, te, th = O2_RICH[off]
        R(cs("P", base + off), "medium", causes, te, th)

# ---- fuel trim ----------------------------------------------------------------
LEAN = [C_VAC, ("Dirty or faulty MAF sensor", "חיישן MAF מלוכלך או תקול"),
        ("Weak fuel pump or clogged fuel filter", "משאבת דלק חלשה או מסנן דלק סתום"),
        ("Clogged fuel injectors", "מזרקים סתומים"), ("Exhaust leak before the front O2 sensor", "דליפת פליטה לפני חיישן ה-O2 הקדמי")]
RICHC = [("Leaking fuel injector", "מזרק דולף"), ("Fuel pressure too high", "לחץ דלק גבוה מדי"),
         ("Dirty air filter or MAF sensor", "מסנן אוויר או חיישן MAF מלוכלכים"),
         ("Faulty O2 or coolant temperature sensor", "חיישן O2 או חיישן טמפרטורה תקול"),
         ("EVAP purge valve stuck open", "שסתום Purge של EVAP תקוע פתוח")]
LEAN_TIP_EN = "Look for vacuum leaks (listen for hissing at idle, check the PCV and intake hoses) and clean the MAF sensor before replacing parts."
LEAN_TIP_HE = "חפשו דליפות ואקום (שריקה בסרק, צינורות PCV ויניקה) ונקו את חיישן ה-MAF לפני שמחליפים חלקים."
RICH_TIP_EN = "Check the air filter, look for fuel smell or black smoke, and check the fuel pressure; a leaking injector is a common cause."
RICH_TIP_HE = "בדקו את מסנן האוויר, שימו לב לריח דלק או עשן שחור ובדקו את לחץ הדלק; מזרק דולף הוא סיבה נפוצה."
R(["P0171", "P0174"], "medium", LEAN, LEAN_TIP_EN, LEAN_TIP_HE)
R(["P0172", "P0175"], "medium", RICHC, RICH_TIP_EN, RICH_TIP_HE)
R("P2187", "medium", [C_VAC, ("Faulty PCV valve or hose", "שסתום או צינור PCV תקולים"),
                      ("Leaking intake manifold gasket", "אטם סעפת יניקה דולף"), ("Low fuel pressure", "לחץ דלק נמוך")],
  "A lean condition only at idle points to a vacuum leak: inspect the PCV and small vacuum hoses.",
  "תערובת דלה רק בסרק מצביעה על דליפת ואקום: בדקו את ה-PCV ואת צינורות הוואקום הקטנים.")
R("P2188", "medium", [("Leaking fuel injector", "מזרק דולף"), ("EVAP purge valve stuck open", "שסתום Purge של EVAP תקוע פתוח"),
                      ("High fuel pressure", "לחץ דלק גבוה"), ("Dirty air filter", "מסנן אוויר מלוכלך")],
  "Check the EVAP purge valve (it should not pass vacuum at idle with the engine cold) and the injectors.",
  "בדקו את שסתום ה-Purge של EVAP (אסור שיעביר ואקום בסרק במנוע קר) ואת המזרקים.")
R(["P2096", "P2098"], "medium",
  [("Exhaust leak near the rear O2 sensor", "דליפת פליטה ליד חיישן ה-O2 האחורי"), C_VAC,
   ("Faulty rear O2 sensor", "חיישן O2 אחורי תקול"), ("Worn catalytic converter", "ממיר קטליטי שחוק")],
  "Fix exhaust and vacuum leaks first; this code often appears together with P0171/P0174 or a catalyst code.",
  "תקנו קודם דליפות פליטה וואקום; הקוד מופיע לעיתים קרובות יחד עם P0171/P0174 או קוד ממיר קטליטי.")
R(["P2097", "P2099"], "medium",
  [("Leaking injector or high fuel pressure", "מזרק דולף או לחץ דלק גבוה"), ("Faulty rear O2 sensor", "חיישן O2 אחורי תקול"),
   ("Worn catalytic converter", "ממיר קטליטי שחוק"), ("Faulty front O2 / air-fuel sensor", "חיישן O2/יחס אוויר-דלק קדמי תקול")],
  "Check for a rich condition and compare the front and rear O2 signals in live data.",
  "בדקו אם יש תערובת עשירה והשוו את אותות חיישני ה-O2 הקדמי והאחורי בנתונים חיים.")
R(["P2195", "P2196"], "medium",
  [("Aging front O2 / air-fuel ratio sensor", "חיישן O2/יחס אוויר-דלק קדמי מזדקן"), C_EXLEAK,
   ("Actual mixture problem (vacuum or fuel)", "בעיית תערובת אמיתית (ואקום או דלק)"), C_WIRING],
  "Check fuel trims: if they are normal, the front sensor itself is likely biased and needs replacement.",
  "בדקו את תיקוני התערובת (Fuel Trim): אם הם תקינים, החיישן הקדמי עצמו כנראה מוטה ודורש החלפה.")
R(["P2270", "P2271"], "medium",
  [("Aging rear O2 sensor", "חיישן O2 אחורי מזדקן"), ("Exhaust leak near the rear sensor", "דליפת פליטה ליד החיישן האחורי"),
   ("Mixture problem (lean/rich)", "בעיית תערובת (דלה/עשירה)"), C_WIRING],
  "Fix exhaust leaks first; a rear sensor stuck at one value usually needs replacement.",
  "תקנו קודם דליפות פליטה; חיישן אחורי שתקוע על ערך אחד דורש בדרך כלל החלפה.")

# ---- overheating / overspeed / boost ------------------------------------------
R("P0217", "stop",
  [("Low coolant or coolant leak", "נוזל קירור נמוך או דליפה"), ("Thermostat stuck closed", "תרמוסטט תקוע סגור"),
   ("Cooling fan not working", "מאוורר הקירור לא פועל"), ("Failed water pump", "משאבת מים תקולה"),
   ("Clogged radiator or head gasket failure", "רדיאטור סתום או אטם ראש שרוף")],
  "Pull over and switch off the engine as soon as it is safe. Let it cool before opening the coolant cap, then check the coolant level and fans.",
  "עצרו בצד וכבו את המנוע ברגע שבטוח. המתינו שיתקרר לפני פתיחת מכסה נוזל הקירור, ואז בדקו מפלס נוזל ומאווררים.")
R("P0218", "stop",
  [("Low transmission fluid", "שמן גיר נמוך"), ("Towing or heavy load in hot weather", "גרירה או עומס כבד במזג אוויר חם"),
   ("Clogged transmission cooler", "מצנן שמן גיר סתום"), ("Slipping torque converter", "ממיר מומנט מחליק"),
   ("Faulty fluid temperature sensor", "חיישן טמפרטורת שמן גיר תקול")],
  "Stop and let the transmission cool down with the engine idling in Park. Check the fluid level and condition before driving on.",
  "עצרו ותנו לתיבה להתקרר כשהמנוע בסרק במצב P. בדקו את מפלס ומצב שמן הגיר לפני שממשיכים.")
R("P0219", "high",
  [("Over-revving (wrong downshift)", "הגעה לסל\"ד גבוה מדי (הורדת הילוך שגויה)"),
   ("Faulty crankshaft position sensor signal", "אות חיישן גל ארכובה לא תקין"),
   ("Diesel runaway / oil entering intake", "דיזל 'בורח' / שמן נכנס ליניקה"), ("Slipping transmission", "תיבה מחליקה")],
  "If it was a one-off over-rev, clear the code and monitor; if the engine revs uncontrollably, shut it off immediately.",
  "אם זה היה אירוע חד-פעמי של סל\"ד גבוה, מחקו את הקוד ועקבו; אם המנוע מאיץ ללא שליטה, כבו אותו מיד.")
R("P0234", "high",
  [("Wastegate stuck closed", "Wastegate תקוע סגור"), ("Faulty boost control solenoid", "סולנואיד בקרת לחץ טורבו תקול"),
   ("Sticking VGT vanes", "להבי טורבו משתנה (VGT) תקועים"), ("Faulty boost pressure sensor", "חיישן לחץ טורבו תקול")],
  "Avoid hard acceleration: overboost can damage the engine. Check the wastegate/VGT actuator and its vacuum lines.",
  "הימנעו מהאצות חזקות: לחץ טורבו גבוה מדי עלול להזיק למנוע. בדקו את מפעיל ה-Wastegate/VGT ואת צינורות הוואקום שלו.")
R("P0299", "medium",
  [("Boost leak (split hose, intercooler)", "דליפת לחץ (צינור קרוע, אינטרקולר)"), ("Wastegate stuck open", "Wastegate תקוע פתוח"),
   ("Sticking VGT vanes", "להבי טורבו משתנה (VGT) תקועים"), ("Worn turbocharger", "טורבו שחוק"),
   ("Clogged air filter or DPF", "מסנן אוויר או DPF סתומים")],
  "Listen for a whistling or hissing sound under load and check all turbo hoses and clamps; a loose clamp is a common cheap fix.",
  "הקשיבו לשריקה בזמן האצה ובדקו את כל צינורות וחבקי הטורבו; חבק רופף הוא תיקון זול ונפוץ.")

# ---- misfire / ignition ---------------------------------------------------------
R("P0300", "high",
  [("Worn spark plugs", "מצתים שחוקים"), ("Faulty ignition coil(s)", "סלילי הצתה תקולים"), C_VAC,
   ("Low fuel pressure or poor fuel", "לחץ דלק נמוך או דלק באיכות ירודה"), ("Low compression", "דחיסה נמוכה")],
  "If the check-engine light flashes, slow down and avoid hard acceleration: unburned fuel can overheat the catalytic converter.",
  "אם נורית תקלת המנוע מהבהבת, האטו והימנעו מהאצות: דלק לא שרוף עלול לחמם ולהרוס את הממיר הקטליטי.")
for c in range(1, 13):
    R(cs("P", 300 + c), "high",
      [("Worn or fouled spark plug", "מצת שחוק או מפויח"), ("Faulty ignition coil", "סליל הצתה תקול"),
       ("Clogged or leaking injector", "מזרק סתום או דולף"), ("Low compression (valve, rings)", "דחיסה נמוכה (שסתום, טבעות)"),
       ("Vacuum leak near this cylinder", "דליפת ואקום ליד הצילינדר")],
      "Swap the coil with another cylinder and see if the misfire moves; if it does, replace the coil.",
      "החליפו את סליל ההצתה עם צילינדר אחר ובדקו אם התקלה עוברת איתו; אם כן – החליפו את הסליל.")
for k in range(8):
    R(cs("P", 351 + k), "high",
      [("Faulty ignition coil", "סליל הצתה תקול"), C_WIRING, ("Worn spark plug overloading the coil", "מצת שחוק שמעמיס על הסליל"), C_ECM],
      "Check the coil connector, then swap the coil with a neighbour to confirm. A misfire on that cylinder usually comes with this code.",
      "בדקו את מחבר הסליל ואז החליפו אותו עם סליל שכן כדי לאשר. בדרך כלל מופיעה גם תקלת הצתה בצילינדר הזה.")
R("P0325", "medium",
  [("Faulty knock sensor", "חיישן נקישות תקול"), C_WIRING, ("Sensor not torqued correctly", "חיישן לא מהודק במומנט הנכון"), C_ECM],
  "Engine may run with retarded timing (less power, more fuel). Inspect the sensor connector, often located under the intake manifold.",
  "המנוע עלול לעבוד עם תזמון מאוחר (פחות כוח, יותר דלק). בדקו את מחבר החיישן, שלרוב נמצא מתחת לסעפת היניקה.")
R("P0335", "high",
  [("Faulty crankshaft position sensor", "חיישן גל ארכובה תקול"), C_WIRING, ("Damaged reluctor ring", "גלגל שיניים של החיישן פגום"),
   ("Metal debris on the sensor", "שבבי מתכת על החיישן")],
  "The engine may stall or not start. Check the sensor connector and air gap; the sensor itself is a common failure.",
  "המנוע עלול לכבות או לא להתניע. בדקו את מחבר החיישן ואת המרווח שלו; החיישן עצמו נכשל לעיתים קרובות.")
R("P0340", "medium",
  [("Faulty camshaft position sensor", "חיישן גל זיזים תקול"), C_WIRING, ("Timing belt/chain problem", "בעיה ברצועת/שרשרת התזמון"),
   ("Damaged reluctor on the camshaft", "גלגל שיניים בגל הזיזים פגום")],
  "Expect long cranking times. Check the sensor connector and wiring before replacing the sensor.",
  "צפו להתנעה ארוכה. בדקו את מחבר וחיווט החיישן לפני החלפתו.")
R("P0341", "medium",
  [("Stretched timing chain or jumped belt", "שרשרת תזמון מתוחה או רצועה שקפצה"), ("Faulty camshaft sensor", "חיישן גל זיזים תקול"),
   ("Damaged reluctor wheel", "גלגל שיניים של החיישן פגום"), ("Electrical interference from ignition wiring", "הפרעה חשמלית מחיווט ההצתה")],
  "If P0016 is also present, suspect the timing chain; otherwise start with the sensor and its wiring.",
  "אם מופיע גם P0016 – חשדו בשרשרת התזמון; אחרת התחילו מהחיישן והחיווט שלו.")
R("P0380", "medium",
  [("Burned-out glow plug(s)", "מצתי להט שרופים"), ("Faulty glow plug relay/module", "ממסר/מודול מצתי להט תקול"), C_WIRING],
  "Hard starting in cold weather is typical. Measure each glow plug's resistance and check the glow plug relay.",
  "התנעה קשה במזג אוויר קר אופיינית לתקלה. מדדו התנגדות של כל מצת להט ובדקו את הממסר.")

# ---- EGR / secondary air / catalyst ---------------------------------------------
R("P0400", "medium",
  [("Carbon-clogged EGR valve or passages", "שסתום או מעברי EGR סתומים בפיח"), ("Faulty EGR valve", "שסתום EGR תקול"),
   ("Faulty EGR sensor/position sensor", "חיישן EGR תקול"), C_WIRING],
  "Have the EGR valve and passages inspected and cleaned; carbon buildup is the most common cause.",
  "בקשו לבדוק ולנקות את שסתום ומעברי ה-EGR; הצטברות פיח היא הסיבה הנפוצה ביותר.")
R("P0401", "medium",
  [("Carbon-clogged EGR passages", "מעברי EGR סתומים בפיח"), ("Stuck or faulty EGR valve", "שסתום EGR תקוע או תקול"),
   ("Leaking vacuum line to the EGR", "צינור ואקום ל-EGR דולף"), ("Faulty DPFE/EGR pressure sensor", "חיישן לחץ EGR (DPFE) תקול")],
  "Cleaning the EGR valve and intake passages often fixes this. Pinging under load can be a symptom.",
  "ניקוי שסתום ה-EGR ומעברי היניקה פותר לרוב את התקלה. נקישות בעומס עשויות להיות סימפטום.")
R("P0402", "medium",
  [("EGR valve stuck open", "שסתום EGR תקוע פתוח"), ("Faulty EGR control solenoid", "סולנואיד בקרת EGR תקול"),
   ("Faulty EGR position sensor", "חיישן מיקום EGR תקול")],
  "Rough idle or stalling at stops is typical; check that the EGR valve closes fully.",
  "סרק לא יציב או כיבוי ברמזורים אופייניים; ודאו ששסתום ה-EGR נסגר עד הסוף.")
R("P0403", "medium",
  [("Faulty EGR valve/solenoid", "שסתום/סולנואיד EGR תקול"), C_WIRING, C_FUSE],
  "Measure the EGR solenoid resistance and check its connector; replace the valve if the coil is open.",
  "מדדו את התנגדות סולנואיד ה-EGR ובדקו את המחבר; אם הסליל קטוע – החליפו את השסתום.")
R("P0411", "low",
  [("Failed secondary air pump", "משאבת אוויר משני תקולה"), ("Stuck check/switching valve", "שסתום חד-כיווני/מיתוג תקוע"),
   ("Cracked hoses", "צינורות סדוקים"), C_FUSE],
  "Listen for the air pump running for about a minute after a cold start; if silent, check its fuse and relay.",
  "הקשיבו אם משאבת האוויר פועלת כדקה אחרי התנעה קרה; אם היא שקטה – בדקו נתיך וממסר.")
CAT = [("Worn or damaged catalytic converter", "ממיר קטליטי שחוק או פגום"), ("Faulty rear (downstream) O2 sensor", "חיישן O2 אחורי (אחרי הממיר) תקול"),
       ("Exhaust leak", "דליפה במערכת הפליטה"), ("Engine misfire or oil burning damaged the converter", "תקלות הצתה או שריפת שמן שפגעו בממיר")]
R(["P0420", "P0430"], "medium", CAT,
  "Fix any misfire or exhaust leak first and check the rear O2 sensor; replace the catalytic converter only after those are ruled out.",
  "תקנו קודם תקלות הצתה ודליפות פליטה ובדקו את חיישן ה-O2 האחורי; החליפו ממיר קטליטי רק אחרי שאלה נשללו.")

# ---- EVAP ----------------------------------------------------------------------
R("P0440", "low", [C_CAP, ("Cracked EVAP hoses", "צינורות EVAP סדוקים"), ("Faulty purge or vent valve", "שסתום Purge או Vent תקול"),
                   ("Damaged charcoal canister", "מיכל פחם פעיל פגום")],
  "Start with the fuel cap: tighten it until it clicks or replace it, then clear the code.",
  "התחילו ממכסה הדלק: הדקו עד קליק או החליפו אותו, ואז מחקו את הקוד.")
R("P0441", "low", [("Faulty purge valve", "שסתום Purge תקול"), ("Blocked or disconnected purge hose", "צינור Purge חסום או מנותק"),
                   ("Clogged charcoal canister", "מיכל פחם פעיל סתום"), C_WIRING],
  "Check that the purge valve opens and closes when commanded and that its hoses are intact.",
  "בדקו ששסתום ה-Purge נפתח ונסגר בפקודה ושהצינורות שלו שלמים.")
R("P0442", "low", [C_CAP, ("Small crack in an EVAP hose", "סדק קטן בצינור EVAP"), ("Leaking purge or vent valve", "שסתום Purge או Vent דולף"),
                   ("Leaking filler neck seal", "אטם צוואר המילוי דולף")],
  "Replace or reseat the fuel cap first; a smoke test at a garage will locate small leaks.",
  "החליפו או הדקו קודם את מכסה הדלק; בדיקת עשן במוסך תאתר דליפות קטנות.")
R("P0446", "low", [("Stuck or faulty vent valve", "שסתום Vent תקוע או תקול"), ("Charcoal canister clogged with dust", "מיכל פחם פעיל סתום באבק"), C_WIRING],
  "The vent valve sits near the charcoal canister (often by the fuel tank) and clogs with dirt; inspect and clean or replace it.",
  "שסתום ה-Vent נמצא ליד מיכל הפחם (לרוב ליד מיכל הדלק) ונסתם מלכלוך; בדקו ונקו או החליפו.")
R("P0449", "low", [("Faulty vent solenoid", "סולנואיד Vent תקול"), C_WIRING, C_FUSE],
  "Inspect the vent solenoid connector under the car for corrosion; replace the solenoid if its coil is open.",
  "בדקו את מחבר סולנואיד ה-Vent מתחת לרכב לחלודה; החליפו אותו אם הסליל קטוע.")
R("P0455", "low", [("Fuel cap missing or loose", "מכסה דלק חסר או רופף"), ("Disconnected EVAP hose", "צינור EVAP מנותק"),
                   ("Vent valve stuck open", "שסתום Vent תקוע פתוח"), ("Cracked charcoal canister", "מיכל פחם פעיל סדוק")],
  "Check the fuel cap first, then look for a disconnected hose; a fuel smell around the car confirms a large leak.",
  "בדקו קודם את מכסה הדלק ואז חפשו צינור מנותק; ריח דלק סביב הרכב מאשר דליפה גדולה.")
R("P0456", "low", [C_CAP, ("Tiny crack in an EVAP hose", "סדק זעיר בצינור EVAP"), ("Leaking purge valve", "שסתום Purge דולף")],
  "Start by tightening or replacing the fuel cap; the code clears after several drive cycles if the leak is fixed.",
  "התחילו בהידוק או החלפת מכסה הדלק; אם הדליפה תוקנה, הקוד יימחק לבד אחרי כמה נסיעות.")
R("P0457", "low", [("Fuel cap loose or left off", "מכסה הדלק רופף או לא הוחזר"), ("Damaged cap seal", "אטם המכסה פגום"),
                   ("Damaged filler neck", "צוואר מילוי פגום")],
  "Tighten the fuel cap until it clicks (or replace it); the light should go out after a few days of driving.",
  "הדקו את מכסה הדלק עד קליק (או החליפו אותו); הנורית אמורה לכבות אחרי כמה ימי נסיעה.")
R("P0496", "low", [("Purge valve stuck open", "שסתום Purge תקוע פתוח"), C_WIRING, ("Faulty EVAP pressure sensor", "חיישן לחץ EVAP תקול")],
  "Hard starting right after refuelling is a typical symptom; replacing the purge valve usually fixes it.",
  "התנעה קשה מיד אחרי תדלוק היא סימפטום אופייני; החלפת שסתום ה-Purge פותרת בדרך כלל.")

# ---- speed / idle / oil pressure / voltage ---------------------------------------
R("P0500", "medium", [("Faulty vehicle speed sensor", "חיישן מהירות רכב תקול"), C_WIRING,
                      ("Faulty ABS wheel speed sensor (on cars that use it)", "חיישן מהירות גלגל ABS תקול (ברכבים שמשתמשים בו)"),
                      ("Instrument cluster fault", "תקלה בלוח המחוונים")],
  "The speedometer and gear shifting may behave oddly; check the sensor connector and any ABS warning light.",
  "מד המהירות והחלפת ההילוכים עלולים להתנהג מוזר; בדקו את מחבר החיישן והאם דולקת נורית ABS.")
IDLE = [("Dirty throttle body / idle air passage", "גוף מצערת / מעבר אוויר סרק מלוכלך"), C_VAC, ("Faulty idle air control valve", "שסתום בקרת סרק תקול"),
        ("Throttle needs an idle relearn", "נדרש לימוד סרק למצערת")]
R("P0505", "medium", IDLE,
  "Clean the throttle body and idle valve with throttle cleaner, then perform an idle relearn if your car requires one.",
  "נקו את גוף המצערת ושסתום הסרק בתרסיס מתאים, ואז בצעו לימוד סרק אם הרכב דורש.")
R("P0506", "medium", [("Dirty throttle body", "גוף מצערת מלוכלך"), ("Restricted air intake", "יניקת אוויר חסומה"),
                      ("Faulty idle air control valve", "שסתום בקרת סרק תקול"), ("Extra engine load (A/C, alternator)", "עומס נוסף על המנוע (מזגן, אלטרנטור)")],
  "Cleaning the throttle body fixes most low-idle complaints.",
  "ניקוי גוף המצערת פותר את רוב תלונות הסרק הנמוך.")
R("P0507", "medium", [C_VAC, ("Dirty or stuck idle air control valve", "שסתום בקרת סרק מלוכלך או תקוע"),
                      ("Throttle needs relearn", "נדרש לימוד מצערת"), ("Faulty PCV valve", "שסתום PCV תקול")],
  "High idle usually means extra air: look for vacuum leaks and clean the throttle body.",
  "סרק גבוה פירושו בדרך כלל אוויר עודף: חפשו דליפות ואקום ונקו את גוף המצערת.")
OILP = [("Faulty oil pressure sensor/switch", "חיישן/מתג לחץ שמן תקול"), C_WIRING, ("Low engine oil level", "מפלס שמן מנוע נמוך"),
        ("Worn oil pump or engine bearings", "משאבת שמן או מיסבים שחוקים")]
OILP_TIP_EN = "Check the oil level immediately. If the oil-pressure warning light is on, stop the engine. Often it is only the sensor, but confirm with a mechanical gauge."
OILP_TIP_HE = "בדקו את מפלס השמן מיד. אם נורית לחץ השמן דולקת – כבו את המנוע. לרוב מדובר רק בחיישן, אבל יש לאשר עם מד לחץ מכני."
R(["P0520", "P0521", "P0522", "P0523"], "stop", OILP, OILP_TIP_EN, OILP_TIP_HE)
R("P0524", "stop", [("Low engine oil level", "מפלס שמן מנוע נמוך"), ("Worn oil pump", "משאבת שמן שחוקה"),
                    ("Clogged oil pickup or filter", "רשת יניקת שמן או מסנן סתומים"), ("Wrong oil viscosity", "צמיגות שמן לא מתאימה"),
                    ("Faulty oil pressure sensor", "חיישן לחץ שמן תקול")],
  "Stop the engine as soon as safely possible and check the oil level; driving with low oil pressure can destroy the engine within minutes.",
  "עצרו וכבו את המנוע ברגע שבטוח ובדקו את מפלס השמן; נסיעה עם לחץ שמן נמוך עלולה להרוס מנוע בתוך דקות.")
R("P0562", "high", [C_BATT, ("Faulty alternator or voltage regulator", "אלטרנטור או וסת מתח תקולים"),
                    ("Loose or corroded battery terminals", "הדקי מצבר רופפים או מחומצנים"), ("Worn or loose drive belt", "רצועת אביזרים שחוקה או רופפת")],
  "Measure the battery voltage with the engine running (should be about 13.8–14.6 V); clean the battery terminals and check the alternator.",
  "מדדו מתח מצבר כשהמנוע פועל (אמור להיות כ-13.8–14.6 וולט); נקו את הדקי המצבר ובדקו את האלטרנטור.")
R("P0563", "high", [("Faulty voltage regulator / alternator", "וסת מתח / אלטרנטור תקולים"), ("Incorrect jump-start or charger", "התנעה בכבלים או מטען לא תקינים"),
                    ("Poor ground between engine and body", "הארקה לקויה בין המנוע למרכב")],
  "Voltage above about 15 V can damage electronics and the battery; have the charging system tested soon.",
  "מתח מעל כ-15 וולט עלול לפגוע באלקטרוניקה ובמצבר; בדקו את מערכת הטעינה בהקדם.")
R("P0571", "medium", [("Misadjusted or faulty brake light switch", "מתג אור בלמים לא מכוון או תקול"), C_WIRING, ("Blown brake light fuse", "נתיך אורות בלמים שרוף")],
  "Ask someone to check that the brake lights work; cruise control is usually disabled with this code.",
  "בקשו ממישהו לבדוק שאורות הבלמים נדלקים; בקרת השיוט בדרך כלל מושבתת עם קוד זה.")

# ---- computer -------------------------------------------------------------------
R("P0600", "high", [("CAN/serial wiring fault", "תקלה בחיווט קו התקשורת"), C_BATT, ("Internal ECM fault", "תקלה פנימית במחשב המנוע")],
  "Check battery voltage and the main grounds first, then scan all modules for communication codes.",
  "בדקו קודם את מתח המצבר וההארקות הראשיות, ואז סרקו את כל המחשבים לקודי תקשורת.")
R("P0601", "high", [("Corrupted ECM memory", "זיכרון מחשב המנוע פגום"), ("Interrupted software update", "עדכון תוכנה שנקטע"), ("Internal ECM failure", "תקלה פנימית במחשב המנוע")],
  "Try clearing the code; if it returns, the ECM needs reprogramming or replacement at a dealer or specialist.",
  "נסו למחוק את הקוד; אם הוא חוזר, מחשב המנוע צריך צריבה מחדש או החלפה אצל מומחה.")
R("P0606", "high", [("Internal ECM processor fault", "תקלה במעבד מחשב המנוע"), C_BATT, ("Water damage or corrosion in the ECM", "נזקי מים או חלודה במחשב"), C_WIRING],
  "Check battery, fuses and ECM grounds first; a software update sometimes fixes it, otherwise the ECM must be replaced.",
  "בדקו מצבר, נתיכים והארקות של המחשב; לעיתים עדכון תוכנה פותר, אחרת יש להחליף את המחשב.")

# ---- transmission ---------------------------------------------------------------
R("P0700", "medium", [("Transmission fault stored in the TCM", "תקלה בתיבה שמורה במחשב התיבה"), ("Low or burnt transmission fluid", "שמן גיר נמוך או שרוף"), C_WIRING],
  "This code means the transmission computer has its own code stored: scan the TCM for the specific code.",
  "הקוד אומר שמחשב תיבת ההילוכים שמר קוד משלו: סרקו את מחשב התיבה לקבלת הקוד המדויק.")
R("P0705", "medium", [("Misadjusted or faulty range sensor", "חיישן בורר הילוכים לא מכוון או תקול"), C_WIRING, ("Stretched shift cable", "כבל הילוכים מתוח")],
  "Check that the gear indicator matches the lever position; the car may not start in P/N with this fault.",
  "בדקו שתצוגת ההילוך תואמת את מיקום הידית; ייתכן שהרכב לא יתניע ב-P/N עם תקלה זו.")
R("P0715", "medium", [("Faulty input/turbine speed sensor", "חיישן מהירות כניסה תקול"), C_WIRING, ("Low transmission fluid", "שמן גיר נמוך"), ("Metal debris on the sensor", "שבבי מתכת על החיישן")],
  "Harsh or erratic shifting and limp mode are typical; inspect the sensor connector on the transmission case.",
  "העברות הילוך קשות או לא סדירות ומצב חירום אופייניים; בדקו את מחבר החיישן על בית התיבה.")
R("P0720", "medium", [("Faulty output speed sensor", "חיישן מהירות יציאה תקול"), C_WIRING, ("Metal debris on the sensor", "שבבי מתכת על החיישן")],
  "Speedometer and shifting may be affected; check the sensor and its connector.",
  "מד המהירות והחלפת ההילוכים עלולים להיפגע; בדקו את החיישן ואת המחבר שלו.")
R("P0730", "high", [("Low or burnt transmission fluid", "שמן גיר נמוך או שרוף"), ("Worn clutches/bands", "מצמדים/רצועות פנימיות שחוקים"),
                    ("Faulty shift solenoid", "סולנואיד העברה תקול"), ("Valve body problem", "תקלה בגוף השסתומים")],
  "Check the fluid level and smell; burnt fluid indicates internal wear. Avoid hard driving to prevent further damage.",
  "בדקו מפלס וריח של שמן הגיר; שמן שרוף מעיד על בלאי פנימי. הימנעו מנהיגה אגרסיבית כדי למנוע נזק נוסף.")
R("P0740", "medium", [("Faulty TCC solenoid", "סולנואיד TCC תקול"), C_WIRING, ("Valve body problem", "תקלה בגוף השסתומים")],
  "Higher RPM at cruise and worse fuel economy are typical; check the TCC solenoid circuit.",
  "סל\"ד גבוה בשיוט וצריכת דלק גבוהה אופייניים; בדקו את מעגל סולנואיד ה-TCC.")
R("P0741", "medium", [("Low or old transmission fluid", "שמן גיר נמוך או ישן"), ("Worn torque converter clutch", "מצמד ממיר המומנט שחוק"),
                      ("Faulty TCC solenoid", "סולנואיד TCC תקול"), ("Valve body wear", "בלאי בגוף השסתומים")],
  "A fluid change sometimes helps; persistent shudder at 60–80 km/h points to the torque converter.",
  "החלפת שמן גיר עוזרת לפעמים; רעידות מתמשכות במהירות 60–80 קמ\"ש מצביעות על ממיר המומנט.")
SOLC = [("Faulty shift solenoid", "סולנואיד העברה תקול"), ("Wiring or connector fault inside the transmission", "תקלה בחיווט או במחבר בתוך התיבה"),
        ("Low or dirty transmission fluid", "שמן גיר נמוך או מלוכלך"), ("Valve body problem", "תקלה בגוף השסתומים")]
R(["P0750", "P0755", "P0770"], "medium", SOLC,
  "The transmission may stay in one gear (limp mode). Check fluid level and the transmission connector before replacing solenoids.",
  "התיבה עלולה להינעל בהילוך אחד (מצב חירום). בדקו את מפלס השמן ואת מחבר התיבה לפני החלפת סולנואידים.")
R("P0841", "medium", [("Faulty fluid pressure sensor/switch", "חיישן/מתג לחץ שמן גיר תקול"), ("Low transmission fluid", "שמן גיר נמוך"),
                      C_WIRING, ("Valve body problem", "תקלה בגוף השסתומים")],
  "Check the transmission fluid level first; the pressure switch is often part of the valve body or solenoid pack.",
  "בדקו קודם את מפלס שמן הגיר; מתג הלחץ הוא לעיתים קרובות חלק מגוף השסתומים או מחבילת הסולנואידים.")

# ---- throttle / pedal -----------------------------------------------------------
R("P2101", "high", [("Dirty or sticking throttle body", "גוף מצערת מלוכלך או תקוע"), ("Faulty throttle motor", "מנוע המצערת תקול"), C_WIRING, C_BATT],
  "Clean the throttle body and check its connector, then perform a throttle relearn. Expect limp mode until fixed.",
  "נקו את גוף המצערת ובדקו את המחבר, ואז בצעו לימוד מצערת. צפו למצב חירום עד לתיקון.")
R("P2135", "high", [("Faulty throttle position sensors in the throttle body", "חיישני מצב המצערת בגוף המצערת תקולים"), C_WIRING, C_ECM],
  "Wiggle-test the throttle body connector with the engine running; usually the throttle body must be replaced.",
  "נענעו את מחבר גוף המצערת כשהמנוע פועל ובדקו שינוי; לרוב יש להחליף את גוף המצערת.")
R("P2138", "high", [("Faulty accelerator pedal position sensor", "חיישן דוושת הגז תקול"), C_WIRING, ("Damaged pedal connector", "מחבר הדוושה פגום")],
  "The car will usually go into reduced power; inspect the pedal connector, the pedal assembly is often replaced as a unit.",
  "הרכב ייכנס בדרך כלל למצב הגבלת כוח; בדקו את מחבר הדוושה – לרוב מחליפים את מכלול הדוושה כולו.")

# ---- diesel aftertreatment ---------------------------------------------------------
R("P242F", "high", [("Ash buildup in the DPF (high mileage)", "הצטברות אפר במסנן החלקיקים (קילומטראז' גבוה)"),
                    ("Wrong engine oil (not low-SAPS)", "שמן מנוע לא מתאים (לא Low-SAPS)"), ("Excessive engine oil consumption", "צריכת שמן גבוהה")],
  "Ash cannot be burned off by regeneration: the DPF must be professionally cleaned or replaced.",
  "אפר לא נשרף ברגנרציה: יש לנקות את מסנן החלקיקים באופן מקצועי או להחליפו.")
R("P2463", "high", [("Many short trips preventing regeneration", "נסיעות קצרות רבות שמונעות רגנרציה"), ("Faulty DPF pressure sensor or hoses", "חיישן לחץ DPF או צינורותיו תקולים"),
                    ("EGR or injector problem causing excess soot", "תקלת EGR או מזרקים שגורמת לעודף פיח"), ("Clogged DPF", "מסנן חלקיקים סתום")],
  "A 20–30 minute drive at steady highway speed may let the filter regenerate; if limp mode persists, a forced regeneration at a garage is needed.",
  "נסיעה של 20–30 דקות במהירות קבועה בכביש מהיר עשויה לאפשר רגנרציה; אם מצב החירום נמשך – נדרשת רגנרציה כפויה במוסך.")
R("P2002", "medium", [("Cracked or damaged DPF", "מסנן חלקיקים סדוק או פגום"), ("Faulty DPF pressure sensor or hoses", "חיישן לחץ DPF או צינורותיו תקולים"),
                      ("Exhaust leak before the DPF", "דליפת פליטה לפני המסנן")],
  "Check the DPF pressure sensor hoses for cracks or disconnection before condemning the filter.",
  "בדקו שצינורות חיישן לחץ ה-DPF לא סדוקים או מנותקים לפני שקובעים שהמסנן תקול.")

# ---- network / chassis / body ----------------------------------------------------
NET = [("CAN bus wiring open or shorted", "חיווט קו ה-CAN קטוע או בקצר"), ("Faulty control module on the bus", "מחשב תקול על קו התקשורת"),
       C_BATT, ("Water ingress in a connector", "חדירת מים למחבר")]
R("U0001", "high", NET,
  "Check battery voltage first, then measure the CAN bus resistance at the OBD socket (about 60 ohms between pins 6 and 14 with ignition off).",
  "בדקו קודם את מתח המצבר, ואז מדדו התנגדות קו ה-CAN בשקע ה-OBD (כ-60 אוהם בין פינים 6 ו-14 כשההצתה כבויה).")
R("U0100", "high", [("ECM power or ground fault (fuse/relay)", "תקלה בהזנה או בהארקה של מחשב המנוע (נתיך/ממסר)"), ("CAN bus wiring fault", "תקלה בחיווט קו ה-CAN"),
                    C_BATT, ("Faulty ECM", "מחשב מנוע תקול")],
  "Check the ECM fuses and battery terminals first; low battery voltage during cranking often sets this code.",
  "בדקו קודם את נתיכי מחשב המנוע ואת הדקי המצבר; מתח מצבר נמוך בהתנעה גורם לא פעם לקוד זה.")
R("U0101", "high", [("TCM power/fuse problem", "בעיה בהזנה/נתיך של מחשב התיבה"), ("CAN bus wiring fault", "תקלה בחיווט קו ה-CAN"), ("Faulty TCM", "מחשב תיבה תקול"), C_BATT],
  "The transmission may be stuck in limp mode. Check the TCM fuse and connector (some TCMs sit inside the gearbox).",
  "תיבת ההילוכים עלולה להיות במצב חירום. בדקו את נתיך ומחבר מחשב התיבה (בחלק מהרכבים הוא בתוך התיבה).")
R("U0121", "high", [("ABS module fuse or power fault", "נתיך או הזנה של מחשב ה-ABS"), ("CAN bus wiring fault", "תקלה בחיווט קו ה-CAN"),
                    ("Internal ABS module failure (corrosion)", "תקלה פנימית במחשב ה-ABS (חלודה)"), C_BATT],
  "ABS and stability control may be off: brake carefully. Check the ABS module fuses and connector.",
  "ייתכן שה-ABS ובקרת היציבות מושבתים: בלמו בזהירות. בדקו את נתיכי ומחבר מחשב ה-ABS.")
R("U0140", "medium", [("BCM fuse or power fault", "נתיך או הזנה של מודול המרכב"), ("CAN bus wiring fault", "תקלה בחיווט קו ה-CAN"),
                      ("Faulty BCM", "מודול מרכב תקול"), ("Water leak onto the BCM", "דליפת מים על מודול המרכב")],
  "Lights, locks or wipers may misbehave. Check BCM fuses and look for water leaks near the module.",
  "פנסים, נעילה או מגבים עלולים לפעול לא תקין. בדקו את נתיכי מודול המרכב וחפשו דליפת מים לידו.")
R("U0155", "medium", [("Instrument cluster fuse or power fault", "נתיך או הזנה של לוח המחוונים"), ("Loose cluster connector", "מחבר לוח המחוונים רופף"),
                      ("CAN bus wiring fault", "תקלה בחיווט קו ה-CAN"), ("Faulty instrument cluster", "לוח מחוונים תקול")],
  "Check the cluster fuse and battery voltage; gauges may stop working while this code is active.",
  "בדקו את נתיך לוח המחוונים ואת מתח המצבר; המחוונים עלולים להפסיק לעבוד כשהקוד פעיל.")
WSS = [("Faulty wheel speed sensor", "חיישן מהירות גלגל תקול"), ("Damaged sensor wiring near the wheel", "חיווט חיישן פגוע ליד הגלגל"),
       ("Dirty or damaged tone ring", "טבעת שיניים מלוכלכת או פגומה"), ("Excessive wheel bearing play", "מיסב גלגל עם חופש מוגזם")]
R(["C0035", "C0040", "C0045", "C0050"], "medium", WSS,
  "ABS and stability control may be disabled; brake normally but carefully. Inspect the sensor wire at that wheel for damage.",
  "ייתכן שה-ABS ובקרת היציבות מושבתים; בלמו כרגיל אך בזהירות. בדקו את חוט החיישן באותו גלגל לנזק.")
R("B0001", "high", [("Faulty clock spring (steering wheel spiral cable)", "סליל שעון (כבל ספירלי בהגה) תקול"), ("Loose airbag connector under the steering wheel", "מחבר כרית אוויר רופף מתחת להגה"),
                    ("Faulty driver airbag module", "כרית אוויר נהג תקולה"), C_WIRING],
  "The airbag may not deploy in a crash. Do not probe airbag circuits yourself: have it checked by a qualified technician.",
  "כרית האוויר עלולה לא להיפתח בתאונה. אל תבדקו מעגלי כריות אוויר בעצמכם: פנו לטכנאי מוסמך.")
R("B0100", "high", [("Faulty frontal crash sensor", "חיישן התנגשות קדמי תקול"), ("Damaged wiring or connector at the front of the car", "חיווט או מחבר פגומים בחזית הרכב"),
                    ("Sensor damaged after a collision", "חיישן שנפגע בתאונה")],
  "Airbag protection may be reduced. Have the SRS system checked, especially after any front-end damage.",
  "הגנת כריות האוויר עלולה להיפגע. בדקו את מערכת ה-SRS, במיוחד אחרי פגיעה בחזית הרכב.")


# ==========================================================================
# Merge, validate, write
# ==========================================================================
CODE_RE = re.compile(r"^[PCBU][0-9][0-9A-F]{3}$")
HEB_RE = re.compile(r"[א-ת]")


def merge_rich():
    for code, (sev, causes, tip_en, tip_he) in RICH.items():
        if code not in CODES:
            raise SystemExit("rich entry for undefined code: %s" % code)
        e = CODES[code]
        e["sev"] = sev
        e["causes_en"] = [c[0] for c in causes]
        e["causes_he"] = [c[1] for c in causes]
        e["tip_en"] = tip_en
        e["tip_he"] = tip_he


def validate():
    errors = []
    for code, e in CODES.items():
        if not CODE_RE.match(code):
            errors.append("%s: bad code format" % code)
        for k in ("en", "he"):
            if not e.get(k, "").strip():
                errors.append("%s: empty %s" % (code, k))
        if not HEB_RE.search(e.get("he", "")):
            errors.append("%s: he has no Hebrew letters" % code)
        if "sev" in e and e["sev"] not in SEVERITIES:
            errors.append("%s: invalid sev %r" % (code, e["sev"]))
        ce, ch = e.get("causes_en"), e.get("causes_he")
        if (ce is None) != (ch is None):
            errors.append("%s: causes_en/causes_he presence mismatch" % code)
        if ce is not None:
            if len(ce) != len(ch):
                errors.append("%s: causes length mismatch" % code)
            if not 2 <= len(ce) <= 5:
                errors.append("%s: causes count %d outside 2..5" % (code, len(ce)))
            for c in ch:
                if not HEB_RE.search(c):
                    errors.append("%s: Hebrew cause without Hebrew letters" % code)
        if ("tip_en" in e) != ("tip_he" in e):
            errors.append("%s: tip_en/tip_he presence mismatch" % code)
        if "tip_he" in e and not HEB_RE.search(e["tip_he"]):
            errors.append("%s: tip_he has no Hebrew letters" % code)
    prefixes = [g["prefix"] for g in GROUPS]
    if len(prefixes) != len(set(prefixes)):
        errors.append("duplicate group prefixes")
    for g in GROUPS:
        if not re.match(r"^[PCBU][0-9][0-9A-F]$", g["prefix"]):
            errors.append("group %s: bad prefix" % g["prefix"])
        if not g["en"] or not HEB_RE.search(g["he"]) or g["sev"] not in SEVERITIES:
            errors.append("group %s: invalid entry" % g["prefix"])
    # every defined code must be covered by a group prefix
    for code in CODES:
        if code[:3] not in prefixes:
            errors.append("%s: no fallback group for prefix %s" % (code, code[:3]))
    for code in RICH:
        if code not in CODES:
            errors.append("%s: rich entry without code" % code)
    return errors


def bucket(code):
    if code.startswith("P34"):
        return "P34"
    return code[:2]


def main():
    merge_rich()
    errors = validate()
    if errors:
        for err in errors[:50]:
            print("ERROR:", err, file=sys.stderr)
        raise SystemExit("validation failed with %d error(s)" % len(errors))

    db = {"version": 1, "codes": dict(sorted(CODES.items())), "groups": GROUPS}
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    data = json.dumps(db, ensure_ascii=False, separators=(",", ":"))
    with open(OUT_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(data)

    # re-load to confirm the file is valid JSON
    with open(OUT_PATH, encoding="utf-8") as f:
        loaded = json.load(f)
    assert loaded["version"] == 1 and len(loaded["codes"]) == len(CODES)

    counts = {}
    for code in CODES:
        counts[bucket(code)] = counts.get(bucket(code), 0) + 1
    rich = sum(1 for e in CODES.values() if "causes_en" in e)
    with_sev = sum(1 for e in CODES.values() if "sev" in e)
    sev_hist = {}
    for e in CODES.values():
        if "sev" in e:
            sev_hist[e["sev"]] = sev_hist.get(e["sev"], 0) + 1
    size = os.path.getsize(OUT_PATH)
    print("Validation: OK")
    print("Output: %s" % OUT_PATH)
    print("Codes per prefix:", ", ".join("%s=%d" % (k, counts.get(k, 0)) for k in ("P0", "P2", "P34", "C0", "B0", "U0")))
    print("Total codes: %d" % len(CODES))
    print("Codes with severity: %d %s" % (with_sev, dict(sorted(sev_hist.items()))))
    print("Codes with rich details: %d" % rich)
    print("Groups: %d" % len(GROUPS))
    print("File size: %d bytes (%.1f KB)" % (size, size / 1024.0))
    if size >= 3 * 1024 * 1024:
        raise SystemExit("output too large")


if __name__ == "__main__":
    main()
