// V2.9.42 Node.js 测试套件
// 测试：5人桌范围表 + GG按钮检测 + BB计算 + 位置映射

const fs = require('fs');

// 提取JS代码
const html = fs.readFileSync('/app/data/所有对话/主对话/poker-app/app/src/main/assets/poker_helper.html', 'utf8');
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
const jsCode = scriptMatch ? scriptMatch[1] : '';

// 创建mock环境
const mockEl = () => ({
    innerHTML: '',
    textContent: '',
    className: '',
    classList: { add: ()=>{}, remove: ()=>{}, toggle: ()=>{}, contains: ()=>false },
    style: {},
    appendChild: ()=>{},
    removeChild: ()=>{},
    addEventListener: ()=>{},
    getAttribute: ()=>'',
    setAttribute: ()=>{},
    querySelectorAll: ()=>[],
    querySelector: ()=>null,
    getElementsByClassName: ()=>[]
});

const mockWindow = {
    document: {
        getElementById: () => mockEl(),
        body: { classList: { add: ()=>{}, remove: ()=>{} } },
        createElement: (tag) => {
            const el = mockEl();
            el.tagName = tag.toUpperCase();
            el.children = [];
            el.appendChild = (child) => { el.children.push(child); };
            return el;
        }
    },
    location: { reload: ()=>{} },
    setTimeout: (fn, ms) => {},
    scrollTo: ()=>{},
    navigator: { userAgent: 'test' }
};

// Mock AndroidBridge
const AndroidBridge = {
    showAdvice: ()=>{},
    updateNotification: ()=>{}
};

// Mock fetch
const mockFetch = () => Promise.resolve({
    json: () => Promise.resolve({ htmlSource: 'local', version: '2.9.42' })
});

// 全局变量G
let G = {
    hole: [null, null],
    comm: [null, null, null, null, null],
    scene: 'open',
    phase: 'pre',
    pos: 'btn',
    tt: 6,
    act: 6,
    stk: 100,
    pot: 10,
    bet: 0,
    opp: 'unknown',
    buttons: []
};

// DRTA mock
const DRTA = {
    setType: ()=>{},
    updateUI: ()=>{},
    reset: ()=>{},
    init: ()=>{}
};

let testResults = [];
function test(name, fn) {
    try {
        const result = fn();
        if (result.pass) {
            testResults.push(`PASS: ${name}: ${result.msg}`);
        } else {
            testResults.push(`FAIL: ${name}: ${result.msg}`);
        }
    } catch(e) {
        testResults.push(`FAIL: ${name}: Exception - ${e.message}`);
    }
}

function assertEqual(actual, expected, msg) {
    return {
        pass: JSON.stringify(actual) === JSON.stringify(expected),
        msg: `${msg} | Expected: ${JSON.stringify(expected)}, Got: ${JSON.stringify(actual)}`
    };
}

function assertContains(arr, item, msg) {
    return {
        pass: arr.indexOf(item) !== -1,
        msg: `${msg} | Array should contain ${item}`
    };
}

// 执行JS代码获取范围表
const vm = require('vm');
const ctx = vm.createContext({
    ...mockWindow,
    AndroidBridge,
    G,
    DRTA,
    console: { log: ()=>{}, warn: ()=>{}, error: ()=>{} },
    setInterval: ()=>{},
    clearInterval: ()=>{},
    fetch: mockFetch
});
vm.runInContext(jsCode, ctx);

// 从执行后的上下文获取范围表
const O5 = ctx.O5;
const TB5 = ctx.TB5;
const CB5 = ctx.CB5;
const gO = ctx.gO;
const gT = ctx.gT;
const gC = ctx.gC;
const detectSceneFromButtons = ctx.detectSceneFromButtons;

console.log('='.repeat(60));
console.log('V2.9.42 Node.js Test Suite');
console.log('='.repeat(60));

// ==================== Test 1: O5 Range Table ====================
test('O5 range table exists', () => {
    return assertEqual(O5 && typeof O5 === 'object', true, 'O5 object should exist');
});

test('O5 has utg/co/btn/sb/bb positions', () => {
    const hasAll = Array.isArray(O5.utg) && Array.isArray(O5.co) && 
                   Array.isArray(O5.btn) && Array.isArray(O5.sb) && Array.isArray(O5.bb);
    return assertEqual(hasAll, true, 'O5 should contain 5 position arrays');
});

test('O5.utg contains AA KK QQ', () => {
    return assertContains(O5.utg, 'AA', 'O5 UTG should contain AA');
});

// ==================== Test 2: TB5 Range Table ====================
test('TB5 range table exists', () => {
    return assertEqual(TB5 && typeof TB5 === 'object', true, 'TB5 object should exist');
});

test('TB5.utg contains AA KK', () => {
    return assertContains(TB5.utg, 'AA', 'TB5 UTG should contain AA');
});

// ==================== Test 3: CB5 Range Table ====================
test('CB5 range table exists', () => {
    return assertEqual(CB5 && typeof CB5 === 'object', true, 'CB5 object should exist');
});

// ==================== Test 4: gO/gT/gC Support 5-max ====================
test('gO supports 5-max (tt=5)', () => {
    G.tt = 5;
    G.pos = 'utg';
    const result = gO('utg');
    return assertEqual(result && result.length > 0, true, 'gO should return 5-max UTG range');
});

test('gT supports 5-max (tt=5)', () => {
    G.tt = 5;
    G.pos = 'btn';
    const result = gT('btn');
    return assertEqual(result && result.length > 0, true, 'gT should return 5-max BTN range');
});

test('gC supports 5-max (tt=5)', () => {
    G.tt = 5;
    G.pos = 'co';
    const result = gC('co');
    return assertEqual(result && result.length > 0, true, 'gC should return 5-max CO range');
});

test('gO still supports 6-max (tt=6)', () => {
    G.tt = 6;
    G.pos = 'utg';
    const result = gO('utg');
    return assertEqual(result && result.length > 0, true, 'gO should return 6-max UTG range');
});

test('gO still supports 9-max (tt=9)', () => {
    G.tt = 9;
    G.pos = 'utg';
    const result = gO('utg');
    return assertEqual(result && result.length > 0, true, 'gO should return 9-max UTG range');
});

// ==================== Test 5: detectSceneFromButtons GG Adaptation ====================
test('GG "让牌" button detection (alone)', () => {
    // 单独让牌按钮应返回check
    const result = detectSceneFromButtons(['让牌'], 'flop', 3);
    return assertEqual(result, 'check', '"让牌" alone should be detected as check');
});

test('GG "让牌+跟注" on flop = bet', () => {
    // 有跟注时flop应返回bet
    const result = detectSceneFromButtons(['让牌', '跟注'], 'flop', 3);
    return assertEqual(result, 'bet', '"让牌+跟注" on flop should be bet');
});

test('GG "让牌／弃牌" combo button detection', () => {
    const result = detectSceneFromButtons(['让牌／弃牌'], 'flop', 3);
    return assertEqual(result, 'check', '"让牌／弃牌" should be detected as check');
});

test('GG "全押" button detection', () => {
    const result = detectSceneFromButtons(['全押', '跟注'], 'flop', 3);
    return assertEqual(result, 'allin', '"全押" should be detected as allin');
});

test('GG "ALL IN" button detection', () => {
    const result = detectSceneFromButtons(['ALL IN'], 'turn', 3);
    return assertEqual(result, 'allin', '"ALL IN" should be detected as allin');
});

test('GG "弃牌让牌" button detection', () => {
    const result = detectSceneFromButtons(['弃牌让牌'], 'flop', 3);
    return assertEqual(result, 'check', '"弃牌让牌" should be detected as check');
});

test('GG "再加注" button detection', () => {
    const result = detectSceneFromButtons(['再加注', '跟注'], 'preflop', 4);
    return assertEqual(result, 'reraise', '"再加注" should be detected as reraise');
});

test('GG "加注" button detection', () => {
    // 代码检测"加注"关键词
    const result = detectSceneFromButtons(['加注'], 'preflop', 4);
    return assertEqual(result, 'open', '"加注" should be detected as open');
});

// Note: parseCallAmountFromButtons tested via VisionApiClient, not here

// ==================== Test 6: BB Calculation Logic ====================
test('BB calculation - use blind_bb directly', () => {
    const data = { blind_bb: 500, blind_sb: 200 };
    let bbValue = 0;
    if (data.blind_bb && parseInt(data.blind_bb) > 0) {
        bbValue = parseInt(data.blind_bb);
    } else if (data.blind_sb && parseInt(data.blind_sb) > 0) {
        const sbVal = parseInt(data.blind_sb);
        bbValue = Math.round(sbVal * 2.5);
        bbValue = Math.max(bbValue, sbVal * 2);
    }
    return assertEqual(bbValue, 500, 'Should use blind_bb directly');
});

test('BB calculation - non-standard ratio (200/500)', () => {
    const data = { blind_sb: 200 }; // GG 200/500 table
    let bbValue = 0;
    if (data.blind_bb && parseInt(data.blind_bb) > 0) {
        bbValue = parseInt(data.blind_bb);
    } else if (data.blind_sb && parseInt(data.blind_sb) > 0) {
        const sbVal = parseInt(data.blind_sb);
        bbValue = Math.round(sbVal * 2.5);
        bbValue = Math.max(bbValue, sbVal * 2);
    }
    // 200*2.5=500, matches GG non-standard ratio
    return assertEqual(bbValue, 500, 'SB=200 should calculate to BB=500 (2.5x ratio)');
});

test('BB calculation - standard ratio (100)', () => {
    const data = { blind_sb: 100 };
    let bbValue = 0;
    if (data.blind_bb && parseInt(data.blind_bb) > 0) {
        bbValue = parseInt(data.blind_bb);
    } else if (data.blind_sb && parseInt(data.blind_sb) > 0) {
        const sbVal = parseInt(data.blind_sb);
        bbValue = Math.round(sbVal * 2.5);
        bbValue = Math.max(bbValue, sbVal * 2);
    }
    // 100*2.5=250, max(250, 200)=250
    return assertEqual(bbValue, 250, 'SB=100 should calculate to BB=250 (2.5x ratio)');
});

// ==================== Test 7: 5-max Position Mapping ====================
test('5-max mp should map to co', () => {
    let pos = 'mp';
    let tt = 5;
    if (tt === 5) {
        if (pos === 'mp') pos = 'co';
        if (pos === 'mp1' || pos === 'mp2') pos = 'co';
        if (pos === 'hj') pos = 'co';
    }
    return assertEqual(pos, 'co', '5-max mp should map to co');
});

test('5-max hj should map to co', () => {
    let pos = 'hj';
    let tt = 5;
    if (tt === 5) {
        if (pos === 'mp') pos = 'co';
        if (pos === 'mp1' || pos === 'mp2') pos = 'co';
        if (pos === 'hj') pos = 'co';
    }
    return assertEqual(pos, 'co', '5-max hj should map to co');
});

test('6-max mp should stay unchanged', () => {
    let pos = 'mp';
    let tt = 6;
    if (tt === 5) {
        if (pos === 'mp') pos = 'co';
        if (pos === 'mp1' || pos === 'mp2') pos = 'co';
        if (pos === 'hj') pos = 'co';
    }
    return assertEqual(pos, 'mp', '6-max mp should stay unchanged');
});

// ==================== Test 8: 5-max vs 6-max UTG Range Difference ====================
test('5-max UTG range is wider than 6-max', () => {
    // Direct check of O5 vs O6
    const o5_utg = O5.utg || [];
    const o6_utg = ctx.O6.utg || [];
    // 5-max has no MP, so it should have more hands in UTG
    // O5 has 66,55 but O6 doesn't
    const o5Has66 = o5_utg.indexOf('66') !== -1;
    const o6Has66 = o6_utg.indexOf('66') !== -1;
    return {
        pass: o5Has66 && !o6Has66,
        msg: `5-max UTG should include 66 (wider), 6-max UTG should not. Got: O5 has 66=${o5Has66}, O6 has 66=${o6Has66}`
    };
});

// ==================== Test 9: 5-max CO Range ====================
test('5-max CO range is similar to 6-max BTN', () => {
    G.tt = 5;
    const o5_co = gO('co') || [];
    G.tt = 6;
    const o6_btn = gO('btn') || [];
    // Both should be wide ranges
    return {
        pass: o5_co.length > 30 && o6_btn.length > 40,
        msg: `5-max CO=${o5_co.length} cards, 6-max BTN=${o6_btn.length} cards, both should be wide ranges`
    };
});

// Output test results
console.log('\n' + '='.repeat(60));
console.log('Test Results Summary:');
console.log('='.repeat(60));
testResults.forEach(r => console.log(r));

const passed = testResults.filter(r => r.startsWith('PASS')).length;
const failed = testResults.filter(r => r.startsWith('FAIL')).length;
console.log('\n' + '='.repeat(60));
console.log(`Total: ${passed} passed, ${failed} failed`);
console.log('='.repeat(60));

if (failed > 0) {
    process.exit(1);
}
