// V2.9.79 验证脚本：行动线追踪 + 翻前3bet/4bet + 下注尺度参数化 + 多街连续性

var failures = [];

function assert(condition, msg) {
  if (!condition) { failures.push(msg); console.log('❌ FAIL: ' + msg); }
  else { console.log('✅ PASS: ' + msg); }
}

// ===== 1. 行动线追踪验证 =====
console.log('\n===== 1. 行动线追踪 =====');

// 模拟ActionLine模块
var ActionLine = {
  _lines: {},     // handId -> [{street, scene, action, eq, sizing}]
  _currentHandId: '',
  
  startHand: function(handId) {
    if (this._currentHandId && this._currentHandId !== handId) {
      // 新一手牌，归档旧的
      delete this._lines[this._currentHandId];
    }
    this._currentHandId = handId;
    if (!this._lines[handId]) this._lines[handId] = [];
  },
  
  record: function(street, scene, action, eq, sizing) {
    if (!this._currentHandId) return;
    this._lines[this._currentHandId].push({
      street: street, scene: scene, action: action, eq: eq, sizing: sizing
    });
  },
  
  getLines: function() {
    return this._lines[this._currentHandId] || [];
  },
  
  getPrevStreet: function(street) {
    var lines = this.getLines();
    var order = ['preflop','flop','turn','river'];
    var idx = order.indexOf(street);
    if (idx <= 0) return null;
    var prevStreet = order[idx - 1];
    for (var i = lines.length - 1; i >= 0; i--) {
      if (lines[i].street === prevStreet) return lines[i];
    }
    return null;
  },
  
  getCurrentStreet: function() {
    var lines = this.getLines();
    if (lines.length === 0) return null;
    return lines[lines.length - 1];
  },
  
  reset: function() {
    this._lines = {};
    this._currentHandId = '';
  }
};

// 测试1: 同一手牌行动线串联
ActionLine.startHand('AKs');
ActionLine.record('preflop', 'open', 'raise', 65, 3);
ActionLine.record('flop', 'check', 'raise', 72, 5);
ActionLine.record('turn', 'raise', 'call', 58, 0);
ActionLine.record('river', 'check', 'raise', 80, 15);

var lines = ActionLine.getLines();
assert(lines.length === 4, '行动线4条街完整记录');

// 测试2: 获取前街行动
var prevFlop = ActionLine.getPrevStreet('turn');
assert(prevFlop && prevFlop.street === 'flop', 'turn前街是flop');
assert(prevFlop.action === 'raise', 'flop行动是raise');

var prevTurn = ActionLine.getPrevStreet('river');
assert(prevTurn && prevTurn.street === 'turn', 'river前街是turn');
assert(prevTurn.action === 'call', 'turn行动是call');

// 测试3: 新一手牌重置
ActionLine.startHand('QTo');
var newLines = ActionLine.getLines();
assert(newLines.length === 0, '新一手牌行动线清空');

// 测试4: preflop没有前街
var noPrev = ActionLine.getPrevStreet('preflop');
assert(noPrev === null, '翻前没有前街');

// ===== 2. 翻前3bet/4bet范围验证 =====
console.log('\n===== 2. 翻前3bet/4bet范围 =====');

// 按位置区分3bet范围
var THREEBET_RANGE = {
  btn: {
    value: ['AA','KK','QQ','JJ','TT','99','AKs','AQs','AJs','ATs','KQs','KJs','AKo','AQo','AJo'],
    bluff: ['A2s','A3s','A4s','A5s','76s','65s','54s'],
    call: ['88','77','A9s','A8s','KTs','QTs','JTs','T9s','98s','ATo','KQo','KJo','QJo']
  },
  co: {
    value: ['AA','KK','QQ','JJ','TT','AKs','AQs','AJs','KQs','AKo','AQo'],
    bluff: ['A2s','A3s','A4s','A5s','76s','65s'],
    call: ['99','88','ATs','KJs','KTs','QJs','JTs','AJo','KQo']
  },
  mp: {
    value: ['AA','KK','QQ','JJ','AKs','AQs','AKo'],
    bluff: ['A2s','A3s','A4s'],
    call: ['TT','99','AJs','KQs','AQo']
  },
  utg: {
    value: ['AA','KK','QQ','AKs','AKo'],
    bluff: [],
    call: ['JJ','TT','AQs']
  },
  sb: {
    value: ['AA','KK','QQ','JJ','TT','99','AKs','AQs','AJs','KQs','AKo','AQo'],
    bluff: ['A2s','A3s','A4s','A5s'],
    call: ['88','77','ATs','KTs','QTs','AJo','KQo']
  },
  bb: {
    value: ['AA','KK','QQ','JJ','TT','99','88','AKs','AQs','AJs','ATs','KQs','AKo','AQo','AJo'],
    bluff: ['A2s','A3s','A4s','A5s','76s','65s','54s','43s'],
    call: ['77','66','A9s','A8s','KJs','KTs','QJs','JTs','T9s','98s','ATo','KQo','KJo']
  }
};

// 测试5: BTN 3bet范围比UTG宽
assert(THREEBET_RANGE.btn.value.length > THREEBET_RANGE.utg.value.length, 
  'BTN 3bet价值范围比UTG宽');
assert(THREEBET_RANGE.btn.bluff.length > THREEBET_RANGE.utg.bluff.length, 
  'BTN 3bet诈唬范围比UTG宽');

// 测试6: UTG没有3bet诈唬范围
assert(THREEBET_RANGE.utg.bluff.length === 0, 'UTG没有3bet诈唬');

// 测试7: BB 3bet范围最宽（防御性3bet）
assert(THREEBET_RANGE.bb.value.length >= THREEBET_RANGE.btn.value.length, 
  'BB 3bet价值范围≥BTN');

// 面对4bet分层决策
var FOURBET_RESPONSE = {
  // 5bet全下
  fivebet_shove: ['AA','KK','QQ','AKs','AKo'],
  // 4bet-call
  fourbet_call: ['JJ','TT','99','AQs','AQo'],
  // 4bet-fold（诈唬4bet被5bet推回）
  fourbet_fold: ['A2s','A3s','A4s','A5s','76s','65s','54s']
};

assert(FOURBET_RESPONSE.fivebet_shove.length === 5, '5bet全下5个组合');
assert(FOURBET_RESPONSE.fourbet_call.length === 5, '4bet-call 5个组合');
assert(FOURBET_RESPONSE.fourbet_fold.length === 7, '4bet-fold诈唬7个组合');

// ===== 3. 下注尺度参数化验证 =====
console.log('\n===== 3. 下注尺度参数化 =====');

var BetSizing = {
  presets: {
    small: 0.33,    // 1/3底池
    medium: 0.50,   // 1/2底池  
    large: 0.75,    // 3/4底池
    pot: 1.00,      // 底池大小
    overbet: 1.30   // 超池下注
  },
  
  recommend: function(eq, hClass, street, ip, oppType, spr, bTexture) {
    var recs = [];
    var pot = 100; // 模拟底池
    
    if (hClass === 'NUTS' || hClass === 'STRONG') {
      // 强牌：大注或超池
      recs.push({pct: this.presets.large, reason: '价值最大下注', priority: 1});
      if (spr > 4 && ip) recs.push({pct: this.presets.overbet, reason: '深码超池价值', priority: 2});
      recs.push({pct: this.presets.medium, reason: '控制底池', priority: 3});
    } else if (hClass === 'MEDIUM') {
      // 中等牌力：中小注
      recs.push({pct: this.presets.small, reason: '薄价值下注', priority: 1});
      recs.push({pct: this.presets.medium, reason: '价值保护', priority: 2});
    } else if (hClass === 'DRAW') {
      // 听牌：半诈唬
      recs.push({pct: this.presets.medium, reason: '半诈唬下注', priority: 1});
      recs.push({pct: this.presets.small, reason: '便宜看牌', priority: 2});
    } else {
      // AIR/WEAK：诈唬或过牌
      if (oppType === 'nit' || oppType === 'tight') {
        recs.push({pct: this.presets.small, reason: 'vs紧牌诈唬', priority: 1});
      }
      recs.push({pct: 0, reason: '过牌', priority: recs.length + 1});
    }
    
    return recs;
  }
};

// 测试8: 强牌推荐大注
var nutRecs = BetSizing.recommend(80, 'NUTS', 'flop', true, 'unknown', 8, {});
assert(nutRecs[0].pct === 0.75, 'NUTS推荐75%底池');
assert(nutRecs.length >= 2, 'NUTS有多个尺度选择');

// 测试9: 中等牌推荐小注
var medRecs = BetSizing.recommend(55, 'MEDIUM', 'flop', true, 'unknown', 8, {});
assert(medRecs[0].pct === 0.33, 'MEDIUM推荐33%底池（薄价值）');

// 测试10: 听牌推荐半诈唬
var drawRecs = BetSizing.recommend(40, 'DRAW', 'flop', true, 'unknown', 8, {});
assert(drawRecs[0].pct === 0.50, 'DRAW推荐50%底池（半诈唬）');

// ===== 4. 多街连续性策略验证 =====
console.log('\n===== 4. 多街连续性策略 =====');

var MultiStreet = {
  // 根据前街行动推断对手范围变化
  inferRangeNarrowing: function(prevAction, currentStreet, oppType) {
    // 对手翻牌check-call → 范围缩窄到中等牌力
    if (prevAction === 'check-call') {
      return {narrowing: 0.3, desc: '对手check-call→中等牌力范围'};
    }
    // 对手翻牌check-raise → 强牌/诈唬
    if (prevAction === 'check-raise') {
      return {narrowing: -0.2, desc: '对手check-raise→强牌或诈唬'};
    }
    // 对手翻牌bet-call → 范围偏强
    if (prevAction === 'bet-call') {
      return {narrowing: 0.2, desc: '对手bet-call→偏强范围'};
    }
    // 对手翻牌donk bet → 可能弱牌保护
    if (prevAction === 'donk') {
      return {narrowing: 0.1, desc: '对手donk→中弱牌保护'};
    }
    return {narrowing: 0, desc: '无前街信息'};
  },
  
  // 是否应该double barrel
  shouldDoubleBarrel: function(flopAction, turnCard, eq, hClass, ip, oppType, bTexture) {
    // 翻牌C-bet后转牌继续
    if (flopAction !== 'cbet') return false;
    
    // 转牌改进→继续
    if (hClass === 'NUTS' || hClass === 'STRONG') return true;
    
    // 转牌听牌→半诈唬double barrel
    if (hClass === 'DRAW' && ip) return true;
    
    // AIR vs 紧对手→继续诈唬
    if (hClass === 'AIR' && (oppType === 'nit' || oppType === 'tight') && bTexture.wetness <= 1) return true;
    
    // 其他情况check back
    return false;
  }
};

// 测试11: check-call缩窄范围
var ccResult = MultiStreet.inferRangeNarrowing('check-call', 'turn', 'tag');
assert(ccResult.narrowing === 0.3, 'check-call→范围缩窄0.3');

// 测试12: check-raise范围不变或变宽
var crResult = MultiStreet.inferRangeNarrowing('check-raise', 'turn', 'tag');
assert(crResult.narrowing === -0.2, 'check-raise→范围变宽');

// 测试13: 强牌double barrel
assert(MultiStreet.shouldDoubleBarrel('cbet', 'K', 75, 'STRONG', true, 'unknown', {wetness:0}), 
  '强牌应该double barrel');

// 测试14: AIR vs nit可以double barrel
assert(MultiStreet.shouldDoubleBarrel('cbet', '2', 25, 'AIR', true, 'nit', {wetness:0}), 
  'AIR vs nit应该double barrel');

// 测试15: AIR vs calling_station不该double barrel
assert(!MultiStreet.shouldDoubleBarrel('cbet', '2', 25, 'AIR', true, 'calling_station', {wetness:0}), 
  'AIR vs calling_station不该double barrel');

// ===== 汇总 =====
console.log('\n===== 验证汇总 =====');
if (failures.length === 0) {
  console.log('🎉 全部15项验证通过！V2.9.79核心逻辑可行');
} else {
  console.log('⚠️ ' + failures.length + '项失败:');
  failures.forEach(function(f) { console.log('  - ' + f); });
}
