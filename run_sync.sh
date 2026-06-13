#!/bin/bash
PIN=$1
if [ -z "$PIN" ]; then
  echo "Usage: ./run_sync.sh <4-digit-PIN>"
  exit 1
fi

echo "1. Force-closing official AirMini app to release Bluetooth lock..."
adb shell am force-stop com.resmed.airmini
sleep 3

# Extract the latest timestamps from existing sleep_data.json
LATEST_TIMESTAMPS="{}"
if [ -f "sleep_data.json" ] && [ -s "sleep_data.json" ]; then
  LATEST_TIMESTAMPS=$(python3 -c '
import json, os, sys
try:
    with open("sleep_data.json", "r") as f:
        data = json.load(f)
    latest = {}
    for k, v in data.items():
        times = []
        for item in v:
            if "events" in item:
                for ev in item["events"]:
                    if "time" in ev:
                        times.append(ev["time"])
            if "periodic" in item:
                if "startTime" in item["periodic"]:
                    times.append(item["periodic"]["startTime"])
        if times:
            latest[k] = max(times)
    print(json.dumps(latest))
except Exception as e:
    print("{}")
')
fi

# Write the latest timestamps to a temporary file and push it to the phone
echo "$LATEST_TIMESTAMPS" > latest_sync.json
adb push latest_sync.json /data/local/tmp/latest_sync.json >/dev/null
rm -f latest_sync.json

echo "2. Running sync process on Android device..."
# Run the Java sync process on the phone, writing new records to a temp file
rm -f new_sleep_data.json
adb shell "LD_LIBRARY_PATH=/data/local/tmp CLASSPATH=/data/local/tmp/sync_v3.jar app_process /system/bin com.resmed.mon.fig.DeviceSync $PIN" > new_sleep_data.json
EXIT_CODE=$?

# Clean up timestamps file on device
adb shell rm -f /data/local/tmp/latest_sync.json

# Validate that the file exists and contains a valid JSON block (ignoring JNI stdout pollution like "HandleResponse")
VALID_JSON=0
if [ $EXIT_CODE -eq 0 ] && [ -s "new_sleep_data.json" ]; then
  python3 -c '
import json, sys
try:
    content = open("new_sleep_data.json", "rb").read()
    idx = content.find(b"{")
    if idx == -1:
        raise ValueError("JSON start not found")
    json.loads(content[idx:])
    sys.exit(0)
except Exception:
    sys.exit(1)
' 2>/dev/null
  VALID_JSON=$?
else
  VALID_JSON=1
fi

if [ $EXIT_CODE -eq 0 ] && [ $VALID_JSON -eq 0 ]; then
  # Merge new data into sleep_data.json
  python3 -c '
import json, os, sys
try:
    old_data = {}
    if os.path.exists("sleep_data.json") and os.path.getsize("sleep_data.json") > 0:
        try:
            with open("sleep_data.json", "r") as f:
                old_data = json.load(f)
        except Exception:
            old_data = {}

    with open("new_sleep_data.json", "rb") as f:
        content = f.read()
    idx = content.find(b"{")
    if idx == -1:
        raise ValueError("No JSON object found in stdout")
    new_data = json.loads(content[idx:])

    merged = {}
    for key in set(old_data.keys()) | set(new_data.keys()):
        old_list = old_data.get(key, [])
        new_list = new_data.get(key, [])
        
        seen = set()
        combined = []
        for item in old_list + new_list:
            sig = None
            if "periodic" in item and "startTime" in item["periodic"]:
                sig = ("periodic", item["periodic"]["startTime"])
            elif "events" in item and item["events"]:
                sig = ("events", item["events"][0]["time"], len(item["events"]))
            else:
                sig = json.dumps(item, sort_keys=True)
            
            if sig not in seen:
                seen.add(sig)
                combined.append(item)
        merged[key] = combined

    with open("sleep_data.json", "w") as f:
        json.dump(merged, f, indent=2)
except Exception as e:
    print("Error merging data:", e)
    sys.exit(1)
'
  rm -f new_sleep_data.json
  echo "3. Success! Synced and decrypted sleep data saved to: sleep_data.json"
else
  rm -f new_sleep_data.json
  echo "3. Sync failed. Please check the error messages above."
  exit 1
fi
