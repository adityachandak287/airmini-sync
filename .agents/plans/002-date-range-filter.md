# 002 — Date Range Selection & Query Filtering

We will implement a date range filter feature with presets (Last Week, Last Month, Last Year) and custom start/end selection using Material 3 UI components, and use this range to request and filter data from the AirMini.

## TODOs

- `[x]` Update `UiState` and `BluetoothViewModel` with state properties to hold selected preset and custom ranges.
- `[ ]` Update `BluetoothViewModel.startSync(pin)` to calculate `fromTime` and `toTime` ISO-8601 timestamps based on the selected range.
- `[ ]` Pass the computed `fromTime` into `AirMiniClient.downloadData(latestTimestamps)`.
- `[ ]` Post-process the downloaded JSON data to filter out records outside `[fromTime, toTime]` before computing stats and exporting.
- `[ ]` Implement the period selection row and custom date range picker dialog in `MainScreen.kt`.
- `[ ]` Compile and verify the build.
