# 002 — Date Range Selection & Query Filtering

We will implement a date range filter feature with presets (Last Week, Last Month, Last Year) and custom start/end selection using Material 3 UI components, and use this range to request and filter data from the AirMini.

## TODOs

- `[x]` Update `UiState` and `BluetoothViewModel` with state properties to hold selected preset and custom ranges.
- `[x]` Update `BluetoothViewModel.startSync(pin)` to calculate `fromTime` and `toTime` ISO-8601 timestamps based on the selected range.
- `[x]` Pass the computed `fromTime` into `AirMiniClient.downloadData(latestTimestamps)`.
- `[x]` Post-process the downloaded JSON data to filter out records outside `[fromTime, toTime]` before computing stats and exporting.
- `[x]` Implement the period selection row and custom date range picker dialog in `MainScreen.kt`.
- `[x]` Compile and verify the build.
