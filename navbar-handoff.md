# Navbar Handoff

## Current State
- Custom bottom navigation bar implemented as a floating `Surface` with `RoundedCornerShape(24.dp)`
- Nav bar renders from ~y=2033 to ~y=2147 (114px ≈ 43.4dp tall)
- `surfaceContainer` color: (42, 42, 42) in dark theme
- Icons visible: white pixels detected at (258, 2078) and (817, 2067) — these are the two nav icons
- System nav bar (48dp/126px) sits below the custom nav bar at y=2284-2410

## What Works
- Build succeeds
- Lint passes (0 errors)
- App runs without crashes
- Nav bar renders surfaceContainer color with 24dp rounded corners
- Icons are visible (white pixels found in icon areas)
- System nav bar padding applied (48dp hardcoded)

## What Needs Fixing

### 1. Nav Bar Height Too Small
- Current nav bar: y=2033 to y=2147 = 114px (43.4dp)
- Expected: 80dp (210px) with 16dp vertical padding = total ~210px
- The `padding(vertical = 16.dp, bottom = 48.dp)` seems to be reducing the effective space
- Need to fix the height calculation

### 2. Corner Radius Verification
- Need to measure the top-left and top-right corner radii
- Expected: 24dp = 63px at the corner where the curve begins
- Anti-aliasing effect: ~2-5px earlier than expected radius
- Should verify corners are properly rounded

### 3. Icon Visibility
- Icons are at (258, 2078) and (817, 2067)
- These should be centered in their 48dp x 48dp icon boxes
- Need to verify icon colors and contrast against surfaceContainer

### 4. Positioning
- Nav bar bottom at y=2147, system nav bar starts at y=2284
- Gap of 137px (52dp) between nav bar and system nav bar
- This gap includes the 16dp (42px) vertical padding
- But 48dp system nav bar padding + 16dp vertical padding should give 64dp = 168px
- The gap seems roughly correct

## Key Files
- `app/src/main/kotlin/com/pixnpu/ui/MainScreen.kt` — main nav bar implementation
- Lines 86-90: `imeVisible` and `navHeight` state (no longer used)
- Lines 94-231: `Box` wrapping `Scaffold`, custom nav bar, and `ParameterSheet`
- The `bottomBar` lambda in `Scaffold` contains the `Surface` with nav bar content

## Measurement Notes
- Device: Pixel 10 Pro, 1080×2410 at 420dpi
- Scale factor: 2.625 px/dp
- System nav bar: 126px = 48dp
- 16dp = 42px
- 24dp = 63px
- 28dp = 73.5px

## Next Steps for Agent
1. Measure corner radii precisely using pixel analysis
2. Verify nav bar height is correct (80dp)
3. Fix any positioning issues
4. Verify icons are rendering correctly
5. Compare against InputBar (28dp corners) for consistency
