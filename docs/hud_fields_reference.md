# HudRoadInfoNotifyStruct — Complete Field Reference

Source: Discope reference (1779 navigation events, U7 DiLink 6.0)
Envelope: outer protobuf field 1 (tag 0x0A) wraps inner message as length-delimited bytes.
**Field ordering is CRITICAL** — the BYD ARHUD parser is non-standard and breaks if fields arrive out of sequence.

---

## Frame fields (in send order)

**f2** — varint int64, ALWAYS
Frame counter, cycling 0–255. Live indicator that the frame is updating.

**f5** — varint int64, ONLY when lanes exist
Number of lane entries. Equals the count of pipe-delimited segments in f29. Must appear BEFORE f6.

**f6** — varint int64, ALWAYS (1779/1779 in reference dump)
Render class selector. Only two values used: `1` = no lane strip, `6` = lane strip present.
Value `255` is reserved for clear-frame only (combined with f16=1).
Values 7/8/9 from factory dump are never reproduced by the reference code — intentional simplification.

**f7** — bytes (PNG), ONLY when lanes exist
Horizontal lane visualization strip. Dimensions: 68 pixels per lane width, 100 pixels tall.
White directional arrows on transparent background. Active lane rendered at full opacity, inactive lanes dimmed.
This is the **sole** field the ARHUD reads for lane visuals. The text in f29 is ignored by the renderer.

**f8** — bytes (PNG), ALWAYS when icon available
80x80 pixel icon. Under normal navigation: maneuver arrow image.
When a speed camera is detected ahead: camera icon replaces the maneuver arrow in this slot.
The maneuver ID in f28 remains unchanged — only the visual changes.

**f9** — varint int64, sent when value > 0
Distance in meters. During normal navigation: distance to next maneuver.
When camera is active: distance to the camera instead.

**f10** — string (UTF-8), ALWAYS
Road or street name for the upcoming maneuver. Empty string if unavailable.

**f11** — varint int64, sent when value > 0
Posted speed limit in km/h. Completely omitted when zero (no limit data).

**f16** — varint int64, ALWAYS
Navigation status code. `2` = navigation active (draw turn card). `1` = clear the HUD display.

**f19** — fixed64 (wire type 1), ONLY when position is known
Current longitude as IEEE 754 double, encoded as 8 little-endian bytes.

**f20** — fixed64 (wire type 1), ONLY when position is known
Current latitude, same encoding as f19.

**f26** — string (UTF-8), ALWAYS
Estimated arrival time in 24-hour format `"HH:MM"`.

**f28** — varint int64, ALWAYS
Maneuver type identifier. In full mode uses ethalon codes:
`1` = straight, `2` = right turn, `3` = left turn, `5` = highway exit, `9` = U-turn.
In non-full mode: raw GAODE maneuver code is sent directly.

**f29** — string (UTF-8), ONLY when lanes exist
Lane direction data as pipe-delimited entries: `"S,H|S,H|..."`.
S = shape code (0=straight, 1=left, 3=right). H = 255 for inactive lane, otherwise = direction code.
Sent alongside f7. Text is ignored by ARHUD but included for protocol completeness.

**f30** — string (UTF-8), ONLY when guideLine is explicitly provided
AR guideline: JSON array of coordinate points defining the route polyline.
**Not sent by default.** Only included when an external caller passes a non-empty guideLine string.
Sending this unconditionally caused regression on Song L (2026-06-28).

**f31** — string (UTF-8), ONLY when guidePoint is explicitly provided
AR maneuver point as string `"$lon,$lat,0"`.
**Not sent by default.** Same guard as f30 — only when explicitly provided.

**f33** — fixed64 (wire type 1), when totalDistMeters > 0 AND distance > 0
Route progress ratio, double value between 0.0 and 1.0.
Formula: `1.0 - (distanceRemaining / totalDistance)`.
Value 0.0 = just started, 1.0 = arrived. This is NOT heading/course.

---

## Fields deliberately OMITTED (confirmed absent from working reference)

- **f3, f4** — total distance/time remaining. Sending them broke rendering of f9 and f26 on Yanwang ROM (2026-06-19 incident).
- **f12** — current vehicle speed. The car renders its own speedometer.
- **f17, f18** — originally labeled as camera flag/distance. Reference code handles cameras via f8 icon swap + f9 distance instead.
- **f21** (=41), **f22** (=50), **f23** (danger sign), **f24** (="[]"), **f25** (="[]") — none of these appear in any of the 1779 reference frames.

---

## Clear frame format

To blank the HUD: send f16=1 and f6=255 together. No other fields needed.

## Timing from reference

- Road info frames: every 200ms (5 per second)
- Map frames (topic 0x8003): every 5th road tick (~1 second). Map channel uses Base64-encoded PNG in field 1.

## Camera handling

Camera detection triggers icon substitution only:
1. Camera icon (80x80 PNG) goes into f8, replacing the maneuver arrow
2. Distance to camera goes into f9, replacing maneuver distance
3. f28 maneuver ID stays unchanged (non-zero)
4. f17/f18 are NOT used for cameras

## Wire types used

- varint (wire type 0): f2, f5, f6, f9, f11, f16, f28
- fixed64 little-endian (wire type 1): f19, f20, f33
- length-delimited bytes (wire type 2): f7 (PNG bytes), f8 (PNG bytes), f10 (string), f26 (string), f29 (string), f30 (string), f31 (string)
