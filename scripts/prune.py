#!/usr/bin/env python3
import json
import sys
import os
from datetime import datetime, timezone

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/prune.py <cutoff-date-YYYY-MM-DD> [input-file-path]")
        print("Example: python3 scripts/prune.py 2026-01-01 sleep_data.json")
        sys.exit(1)
        
    cutoff_str = sys.argv[1]
    file_path = sys.argv[2] if len(sys.argv) > 2 else "sleep_data.json"
    
    try:
        cutoff_dt = datetime.strptime(cutoff_str, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    except ValueError:
        print("Error: Cutoff date must be in YYYY-MM-DD format.")
        sys.exit(1)
        
    if not os.path.exists(file_path):
        print(f"Error: File not found: {file_path}")
        sys.exit(1)
        
    try:
        with open(file_path, "r") as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error parsing JSON: {e}")
        sys.exit(1)
        
    def parse_iso(t_str):
        try:
            return datetime.strptime(t_str.replace("Z", "+00:00"), "%Y-%m-%dT%H:%M:%S.%f%z")
        except ValueError:
            try:
                return datetime.strptime(t_str.replace("Z", "+00:00"), "%Y-%m-%dT%H:%M:%S%z")
            except ValueError:
                return None

    pruned_data = {}
    
    # Prune UsageEvents and TherapyEvents
    for key in ["UsageEvents-TherapyStatusEvent", "TherapyEvents-RespiratoryEvent"]:
        pruned_list = []
        for item in data.get(key, []):
            if "events" in item:
                new_events = []
                for ev in item["events"]:
                    ev_time = parse_iso(ev.get("time"))
                    if ev_time and ev_time >= cutoff_dt:
                        new_events.append(ev)
                if new_events:
                    new_item = item.copy()
                    new_item["events"] = new_events
                    pruned_list.append(new_item)
        pruned_data[key] = pruned_list

    # Prune Periodic data
    for key in ["TherapyOneMinutePeriodic-InspiratoryPressure", "TherapyOneMinutePeriodic-Leak"]:
        pruned_list = []
        for item in data.get(key, []):
            if "periodic" in item and "startTime" in item["periodic"]:
                start_time = parse_iso(item["periodic"]["startTime"])
                if start_time and start_time >= cutoff_dt:
                    pruned_list.append(item)
        pruned_data[key] = pruned_list
        
    backup_path = file_path + ".bak"
    try:
        with open(backup_path, "w") as f:
            json.dump(data, f, indent=2)
            
        with open(file_path, "w") as f:
            json.dump(pruned_data, f, indent=2)
            
        print(f"Success! Pruned data saved to: {file_path}")
        print(f"Original file backed up to:  {backup_path}")
    except Exception as e:
        print(f"Error writing file: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
