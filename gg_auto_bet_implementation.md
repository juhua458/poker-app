# GG Auto-Bet Implementation Plan

## Current State
- Bottom 3 buttons (FOLD/CHECK/RAISE): coordinates confirmed at y=0.960
- Right side has 4 preset bet buttons: 100%/75%/50%/33%
- GG has no bet slider, only preset buttons
- Strategy outputs raise amounts but auto-tap can't input specific amounts

## Implementation Tasks

### 1. Fix All-in Coordinate (High Priority)
**File:** `GameModeConfig.kt`
**Change:** Update allin fallback from (0.93, 0.76) to (0.819, 0.751)

### 2. Bet Amount Auto-Input (High Priority)
**File:** `FloatingService.kt`
**Logic:**
1. Parse strategy's recommended raise amount from decision data
2. Get pot size and current bet from vision API
3. Calculate which % button matches:
   - If raise_amount >= pot * 0.95 → click 100% button (0.819, 0.751)
   - If raise_amount >= pot * 0.70 → click 75% button (0.819, 0.821)
   - If raise_amount >= pot * 0.45 → click 50% button (0.819, 0.890)
   - Otherwise → click 33% button (0.819, 0.937)
4. Add to auto-tap: if action is "raise" or "raise_big", first click the appropriate % button, then click RAISE to confirm

### 3. Shot Clock Protection (Medium Priority)
**File:** `FloatingService.kt`
**Logic:**
1. Detect shot clock timer in screenshot (OCR or vision API)
2. If time_remaining < 8 seconds, prioritize fast auto-tap
3. Add timeout protection: if auto-tap doesn't complete in 15 seconds, force emergency action

### 4. Card Squeeze Handling (Medium Priority)
**File:** `FloatingService.kt` + `VisionApiClient.kt`
**Logic:**
1. Detect squeeze state (cards partially revealed, animation in progress)
2. If squeeze detected, wait 3-5 seconds and retry
3. Add "squeeze_wait" state to prevent false reads

### 5. Insurance Auto-Handle (Low Priority)
**File:** `FloatingService.kt`
**Logic:**
1. Detect Insurance popup (vision API already detects is_insurance)
2. Auto-click "拒绝" (Reject) button
3. Add insurance_decline button position to GameModeConfig

### 6. Manual Game Type Selection UI (Low Priority)
**File:** Floating ball UI
**Logic:**
1. Add long-press menu on floating ball
2. Allow manual selection: Normal/Straddle/Bomb Pot/PKO/Rush & Cash
3. Override auto-detection

## Timeline Estimate
- Task 1 (All-in fix): 5 minutes
- Task 2 (Bet auto-input): 30 minutes
- Task 3 (Shot clock): 20 minutes
- Task 4 (Squeeze): 25 minutes
- Task 5 (Insurance): 15 minutes
- Task 6 (Game type UI): 20 minutes

Total: ~2 hours

## Risk Assessment
- Shot clock detection requires OCR or vision API enhancement
- Squeeze detection needs image analysis (blur detection or card position tracking)
- Bet auto-input assumes strategy outputs raise amounts (need to verify)

## Testing Plan
1. Unit test: Verify coordinate calculations
2. Integration test: Run auto-tap with GG screenshot
3. Live test: Play 10 hands with auto-tap enabled
4. Edge cases: Short stack (all-in), squeeze animation, insurance popup

