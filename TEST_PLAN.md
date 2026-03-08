# Integration Branch Test Plan

Test plan for validating all four feature branches before submitting PRs
to upstream openpnp/openpnp. All tests run on the CHM-T48VB production
machine with the integration jar alongside the existing production OpenPnP.

## Prerequisites

- [ ] Integration branch built and jar copied to pick-and-place machine
- [ ] Production OpenPnP jar preserved separately (do NOT overwrite)
- [ ] Firmware flashed and basic serial communication confirmed (TEST_PLAN.md
      in pick-and-place repo covers this)
- [ ] Machine homed and basic G0 moves working

## Phase 1: Regression — ReferenceDragFeeder Unchanged

The drag feeder extensibility branch changed 4 fields and 1 method from
`private` to `protected`. This must not change any existing behavior.

- [ ] Open the integration jar
- [ ] Load existing machine.xml configuration (all 48 feeders are
      ReferenceDragFeeder instances)
- [ ] Verify all feeders load without errors in the log
- [ ] Open any feeder's configuration wizard — verify all fields populate
- [ ] Run a single feed operation on a known-good feeder
  - Pin extends, drags tape, peel-off fires, backoff, pin retracts
  - Part is at expected pick location
- [ ] Run a second feed on the same feeder — verify multi-part pitch
      tracking works (feededCount increments correctly)
- [ ] Run a feed with vision enabled — verify vision offsets are computed
      and applied

**Pass criteria:** Existing feeders behave identically to production OpenPnP.

## Phase 2: SolenoidSpringDragFeeder

### 2a: Configuration and UI

- [ ] Create a new feeder, verify "Solenoid Spring Drag Feeder" appears
      in the feeder type dropdown
- [ ] Select it — verify configuration wizard opens with all standard
      fields plus "Backoff Speed %"
- [ ] Set Backoff Speed to 10%, verify it saves and reloads
- [ ] Set Backoff Speed to 50%, verify it saves and reloads

### 2b: Migration from ReferenceDragFeeder

- [ ] Copy machine.xml, edit one feeder: change class name from
      `ReferenceDragFeeder` to `SolenoidSpringDragFeeder`
- [ ] Load the edited config — verify the feeder loads without errors
- [ ] Verify backoffSpeed defaults to 0.1 (10%) since the field is absent
      from the existing XML
- [ ] Open the wizard — verify all inherited fields populated from existing
      config values

### 2c: Feed Operation

- [ ] Configure a SolenoidSpringDragFeeder with known-good feed start/end
      locations, actuator name, and peel-off actuator name
- [ ] Set backoff speed to 10%
- [ ] Run a feed and observe the sequence:
  - [ ] Pin extends (solenoid energizes)
  - [ ] Verify pin extension logged or confirmed (actuator read)
  - [ ] Tape drags from start to end
  - [ ] Solenoid de-energizes BEFORE backoff (not after — this is the key
        difference from ReferenceDragFeeder)
  - [ ] Peel-off fires but does NOT deactivate yet
  - [ ] Backoff move runs at visibly slow speed (10%)
  - [ ] Pin retraction verified via actuator read
  - [ ] Peel-off deactivates after pin retraction confirmed
- [ ] Part is at expected pick location

### 2d: Pin Verification

- [ ] Deliberately disconnect or block the drag pin actuator sensor
- [ ] Run a feed — verify the feeder retries pin extension once, then
      throws "Drag pin failed to extend after retry"
- [ ] Reconnect the sensor
- [ ] Run a feed and physically hold the pin down during backoff (prevent
      spring retraction)
- [ ] Verify the double-backoff fallback triggers
- [ ] If the pin still doesn't retract, verify the error message:
      "Drag pin failed to retract after double-backoff"

### 2e: Backoff Speed Tuning

- [ ] Set backoff speed to 5% — run a feed, observe slower backoff
- [ ] Set backoff speed to 50% — run a feed, observe faster backoff
- [ ] Set backoff speed to 100% — run a feed, should match normal feed speed
- [ ] Verify pin retraction succeeds at each speed (lower speeds should
      be more reliable with spring return)

### 2f: Multi-Part and Vision

- [ ] Configure a 0402 tape (2mm pitch) with SolenoidSpringDragFeeder
- [ ] Run a feed — verify feededCount is set to 2
- [ ] Run a second feed — verify it skips the drag and offsets pick location
- [ ] Run a third feed — verify it drags again
- [ ] Enable vision on a SolenoidSpringDragFeeder
- [ ] Run a feed — verify vision offsets computed and applied

## Phase 3: M920 Segment Buffering

This test requires the encoder firmware to be flashed and working. If the
firmware is not ready, skip to Phase 4 and return to this later.

### 3a: Verify M920 Emission

- [ ] Enable `Simulated3rdOrderControl` motion control type in the driver
      configuration
- [ ] Set interpolation parameters (interpolationMaxSteps, etc.) so that
      moves produce multiple segments
- [ ] Enable GCode logging (driver trace level)
- [ ] Command a long move (e.g., pick from one side of the board, place
      on the other)
- [ ] Check the log for `M920 S<n>` commands before batches of G0 commands
  - [ ] Verify `n` matches the number of subsequent G0 commands
  - [ ] Verify M920 is NOT emitted for single-segment moves (short moves)

### 3b: Firmware Interaction

Requires encoder firmware with M920 support.

- [ ] With M920 emitting and encoder firmware, run a multi-segment move
- [ ] Verify the firmware buffers all segments and executes them smoothly
- [ ] Check encoder position after the move: `M918` should match target
- [ ] Run several moves at increasing speeds
- [ ] Run a short placement job to verify end-to-end reliability

### 3c: Non-M920 Firmware Fallback

- [ ] Flash the OLD firmware (no M920 support)
- [ ] Run the integration jar with Simulated3rdOrderControl enabled
- [ ] Verify moves still work — firmware ignores unknown M920 command,
      segments execute as individual G0 commands
- [ ] This confirms the M920 change is safe for machines that don't
      support it

## Phase 4: DrawRotatedRects Debug Overlay

### 4a: Image Center Crosshair

- [ ] Open the bottom vision pipeline editor
- [ ] Find or add a DrawRotatedRects stage
- [ ] Enable `drawImageCenterCrosshair` in the stage properties
- [ ] Place a part on the up-looking camera
- [ ] Verify a crosshair appears at the image center
- [ ] Verify the crosshair does NOT move when the part moves (it's fixed
      at image center, not part center)
- [ ] Disable `drawImageCenterCrosshair` — verify crosshair disappears

### 4b: Details Text Overlay

- [ ] Enable `showDetails` in the DrawRotatedRects stage properties
- [ ] Place a part on the camera
- [ ] Verify text overlay appears showing: `{x, y} WxH=area @ angle`
- [ ] Move the part — verify coordinates update
- [ ] Rotate the part — verify angle updates
- [ ] Disable `showDetails` — verify text disappears

### 4c: Orientation Mark

- [ ] Enable `showOrientation`
- [ ] Verify two lines now extend from the detected rectangle center:
  - One along the orientation angle (existing behavior)
  - One toward the shorter edge (new)
- [ ] The two lines should be visually distinct, making it easy to see
      which axis is which

### 4d: Regression

- [ ] With all new options disabled (defaults), verify DrawRotatedRects
      behaves identically to before — just the rectangle outline, optional
      center circle, optional single orientation line
- [ ] Verify existing pipeline XML files load without errors (new attributes
      are `required = false` with defaults)

## Phase 5: Full Production Test

Only after Phases 1-4 pass.

- [ ] Load the full production board job
- [ ] Run the complete job with the integration jar
- [ ] Monitor for any errors, unexpected behavior, or performance issues
- [ ] Compare placement accuracy to the production OpenPnP jar
- [ ] If using encoder firmware + M920: compare placement speed and
      accuracy vs old firmware + old OpenPnP

## Results

| Test | Pass/Fail | Notes |
|------|-----------|-------|
| Phase 1: ReferenceDragFeeder regression | | |
| Phase 2a: SolenoidSpringDragFeeder UI | | |
| Phase 2b: Config migration | | |
| Phase 2c: Feed operation | | |
| Phase 2d: Pin verification | | |
| Phase 2e: Backoff speed tuning | | |
| Phase 2f: Multi-part and vision | | |
| Phase 3a: M920 emission | | |
| Phase 3b: Firmware interaction | | |
| Phase 3c: Non-M920 fallback | | |
| Phase 4a: Crosshair overlay | | |
| Phase 4b: Details overlay | | |
| Phase 4c: Orientation mark | | |
| Phase 4d: DrawRotatedRects regression | | |
| Phase 5: Full production test | | |
