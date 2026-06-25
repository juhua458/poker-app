#!/usr/bin/env python3
"""V2.9.155: Insert StrategyEngine into poker_helper.html"""
import os

HTML_PATH = '/app/data/所有对话/主对话/poker-app/app/src/main/assets/poker_helper.html'
SE_PATH = '/app/data/所有对话/主对话/poker-app/strategy_engine_v2155.js'

with open(HTML_PATH, 'r', encoding='utf-8') as f:
    lines = f.readlines()

with open(SE_PATH, 'r', encoding='utf-8') as f:
    se_code = f.read()

total_lines = len(lines)
print(f"Original file: {total_lines} lines")

# 1. Find insertion point for StrategyEngine module (after HSM module, before SeatRole)
# HSM module starts with "// ===== V2.9.154: HandStateMachine"
# We insert AFTER the HSM IIFE closes (which is before SeatRole)
se_insert_line = None
for i, line in enumerate(lines):
    if '// ===== V2.9.154: HandStateMachine' in line:
        # Find the end of HSM module - look for the closing })(); 
        # and then the next section
        for j in range(i, min(i+200, total_lines)):
            if lines[j].strip() == '})();' and j > i + 50:
                se_insert_line = j + 1
                break
        break

if se_insert_line is None:
    # Fallback: find SeatRole line
    for i, line in enumerate(lines):
        if 'SeatRole' in line and ('var SeatRole' in line or 'SeatRole=' in line):
            se_insert_line = i
            break

print(f"StrategyEngine insert at line {se_insert_line}")

# 2. Find _decideInner() line where result=cc>0?postF(k):preF(k)
decide_line = None
for i, line in enumerate(lines):
    if 'var result=cc>0?postF(k):preF(k)' in line:
        decide_line = i
        break

print(f"_decideInner result line: {decide_line}")

# 3. Find version lines
version_lines = {}
for i, line in enumerate(lines):
    if "version:'2.9.154'" in line:
        version_lines['G.version'] = i
    elif "CURRENT_VERSION:'2.9.154'" in line:
        version_lines['CacheManager'] = i

print(f"Version lines: {version_lines}")

# 4. Perform insertions
new_lines = lines[:]

# Insert StrategyEngine module
se_block = '\n// ===== V2.9.155: StrategyEngine (GTO频率表+翻后框架+剥削调整) =====\n' + se_code + '\n\n'
new_lines.insert(se_insert_line, se_block)
# Adjust line numbers after this insertion
offset = 1  # 1 block inserted (the se_block is one string entry)

# Update version: G.version
gv_line = version_lines['G.version'] + offset  # no adjustment needed, before SE insert
# Actually need to recalculate since SE is inserted AFTER version lines in most cases
# Let's recalculate
gv_offset = 0
if version_lines['G.version'] >= se_insert_line:
    gv_offset = 1
cm_offset = 0
if version_lines['CacheManager'] >= se_insert_line:
    cm_offset = 1
dec_offset = 1  # decide_line is always after SE insert

new_lines[version_lines['G.version'] + gv_offset] = new_lines[version_lines['G.version'] + gv_offset].replace("version:'2.9.154'", "version:'2.9.155'")
new_lines[version_lines['CacheManager'] + cm_offset] = new_lines[version_lines['CacheManager'] + cm_offset].replace("CURRENT_VERSION:'2.9.154'", "CURRENT_VERSION:'2.9.155'")

# Modify _decideInner() - replace the result assignment
actual_decide_line = decide_line + dec_offset
old_decide = new_lines[actual_decide_line]
new_decide = old_decide.replace(
    'var result=cc>0?postF(k):preF(k);',
    '// V2.9.155: StrategyEngine路由——GTO表优先,旧引擎回退\n'
    '  var result;\n'
    '  if(typeof StrategyEngine!=="undefined"&&StrategyEngine.isEnabled()){\n'
    '    var _seR=cc>0?StrategyEngine.decidePostflop(k):StrategyEngine.decidePreflop(k);\n'
    '    if(_seR){result=_seR;console.log("[SE] GTO决策: "+_seR.a+" "+(_seR._seFreq?Math.round(_seR._seFreq*100)+"%":""));}\n'
    '    else{result=cc>0?postF(k):preF(k);console.log("[SE] GTO未匹配→旧引擎");}\n'
    '  }else{result=cc>0?postF(k):preF(k);}'
)
new_lines[actual_decide_line] = new_decide

# Write the modified file
with open(HTML_PATH, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)

print(f"Modified file: {len(new_lines)} lines")
print("Done!")
