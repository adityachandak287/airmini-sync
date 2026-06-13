#!/usr/bin/env python3
import json
import sys
import os
from datetime import datetime

def parse_iso_time(time_str):
    try:
        # Standard ISO 8601 parsing
        return datetime.strptime(time_str.replace("Z", "+00:00"), "%Y-%m-%dT%H:%M:%S.%f%z")
    except ValueError:
        try:
            return datetime.strptime(time_str.replace("Z", "+00:00"), "%Y-%m-%dT%H:%M:%S%z")
        except ValueError:
            return None

def format_duration(seconds):
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    return f"{hours}h {minutes}m"

def main():
    file_path = sys.argv[1] if len(sys.argv) > 1 else "sleep_data.json"
    
    if not os.path.exists(file_path):
        print(f"Error: File not found: {file_path}")
        sys.exit(1)
        
    try:
        with open(file_path, "r") as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error parsing JSON file: {e}")
        sys.exit(1)

    print("==================================================")
    print(f"       ResMed AirMini Therapy Statistics          ")
    print(f"       Source: {os.path.basename(file_path)}      ")
    print("==================================================")

    # 1. Gather all timestamps to find first and last dates
    all_times = []
    
    # Usage Events
    usage_events = []
    for item in data.get("UsageEvents-TherapyStatusEvent", []):
        for ev in item.get("events", []):
            usage_events.append(ev)
            dt = parse_iso_time(ev.get("time"))
            if dt:
                all_times.append(dt)

    # Respiratory Events
    resp_events = []
    for item in data.get("TherapyEvents-RespiratoryEvent", []):
        for ev in item.get("events", []):
            resp_events.append(ev)
            dt = parse_iso_time(ev.get("time"))
            if dt:
                all_times.append(dt)

    # Periodic Pressure
    pressure_points = 0
    pressure_values = []
    for item in data.get("TherapyOneMinutePeriodic-InspiratoryPressure", []):
        periodic = item.get("periodic", {})
        dt = parse_iso_time(periodic.get("startTime"))
        if dt:
            all_times.append(dt)
        vals = periodic.get("values", [])
        pressure_points += len(vals)
        pressure_values.extend([v for v in vals if v > 0])

    # Periodic Leak
    leak_points = 0
    leak_values = []
    for item in data.get("TherapyOneMinutePeriodic-Leak", []):
        periodic = item.get("periodic", {})
        dt = parse_iso_time(periodic.get("startTime"))
        if dt:
            all_times.append(dt)
        vals = periodic.get("values", [])
        leak_points += len(vals)
        leak_values.extend([v for v in vals if v >= 0])

    if not all_times:
        print("No records or timestamps found in the dataset.")
        return

    first_date = min(all_times)
    last_date = max(all_times)
    
    print(f"Timeline Range: {first_date.strftime('%Y-%m-%d %H:%M:%S %Z')} to {last_date.strftime('%Y-%m-%d %H:%M:%S %Z')}")
    print(f"Total Span:     {(last_date - first_date).days} days")
    print("--------------------------------------------------")

    # Calculate therapy duration (MaskOn to MaskOff)
    total_usage_seconds = 0
    current_mask_on = None
    
    # Sort events chronologically to compute sessions
    sorted_usage = sorted(
        [ev for ev in usage_events if ev.get("time")],
        key=lambda x: parse_iso_time(x["time"])
    )
    
    for ev in sorted_usage:
        event_type = ev.get("event")
        event_time = parse_iso_time(ev["time"])
        if not event_time:
            continue
            
        if event_type == "MaskOn":
            current_mask_on = event_time
        elif event_type == "MaskOff" and current_mask_on:
            diff = (event_time - current_mask_on).total_seconds()
            if diff > 0 and diff < 43200: # filter out missing off bounds (>12h)
                total_usage_seconds += diff
            current_mask_on = None

    print(f"Calculated Total Usage:   {format_duration(total_usage_seconds)}")
    if len(sorted_usage) > 0:
        mask_ons = sum(1 for ev in sorted_usage if ev.get("event") == "MaskOn")
        print(f"Number of Mask Sessions:   {mask_ons}")
    print("--------------------------------------------------")

    # Respiratory Event Breakdown
    if resp_events:
        print("Respiratory Events:")
        event_counts = {}
        total_duration = 0
        dur_count = 0
        for ev in resp_events:
            ev_type = ev.get("event", "Unknown")
            event_counts[ev_type] = event_counts.get(ev_type, 0) + 1
            if "durationSeconds" in ev:
                total_duration += ev["durationSeconds"]
                dur_count += 1
                
        for ev_type, count in sorted(event_counts.items(), key=lambda x: x[1], reverse=True):
            print(f"  - {ev_type}: {count}")
        print(f"  - Total Events: {len(resp_events)}")
        if dur_count > 0:
            print(f"  - Avg Apnea Duration: {total_duration / dur_count:.1f} seconds")
    else:
        print("Respiratory Events: None detected")
    print("--------------------------------------------------")

    # Telemetry Stats
    print("Telemetry Metrics (Minute-by-Minute):")
    if pressure_values:
        avg_p = sum(pressure_values) / len(pressure_values)
        max_p = max(pressure_values)
        min_p = min(pressure_values)
        # 95th percentile pressure (common CPAP stat)
        sorted_p = sorted(pressure_values)
        p95 = sorted_p[int(len(sorted_p) * 0.95)]
        print(f"  - Pressure (cmH2O): Min={min_p:.1f}, Avg={avg_p:.1f}, 95%={p95:.1f}, Max={max_p:.1f}")
    else:
        print("  - Pressure (cmH2O): No data")

    if leak_values:
        avg_l = sum(leak_values) / len(leak_values)
        max_l = max(leak_values)
        min_l = min(leak_values)
        sorted_l = sorted(leak_values)
        l95 = sorted_l[int(len(sorted_l) * 0.95)]
        print(f"  - Leak Rate (L/min): Min={min_l:.2f}, Avg={avg_l:.2f}, 95%={l95:.2f}, Max={max_l:.2f}")
    else:
        print("  - Leak Rate (L/min): No data")
    print("==================================================")

if __name__ == "__main__":
    main()
