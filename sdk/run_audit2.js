var sdk = require('./index.js');
sdk.initEngine();

// Debug: why does nut flush on non-paired board show as DRAW?
// Let me trace handClassify step by step
var hole = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
var comm = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'c'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}];

var cards = [].concat(hole.filter(function(c){return c;}),comm.filter(function(c){return c;}));
console.log('cards:', JSON.stringify(cards));

var flushSuits = {};
cards.forEach(function(c){if(c)flushSuits[c.suit]=(flushSuits[c.suit]||0)+1;});
console.log('flushSuits:', JSON.stringify(flushSuits));
// Expected: spades: 4 (A,K,Q,8) - only 4 spades, need 5 for flush!
// Wait - A♠ K♠ Q♠ 8♠ = 4 spades. We need 5 cards for flush but have only 4
// This is a flush DRAW, not a flush!
// The test is wrong - need one more spade on board

console.log('');
console.log('--- Fixing test: need 5 spades for flush made ---');

// Correct test: 5 spades needed for made flush
var hole2 = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
var comm2 = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'2',suit:'c'},{rank:'3',suit:'d'}];
var result2 = handClassify(hole2, comm2);
console.log('Nut flush (5 spades) → ' + result2.name + ' ' + result2.desc);

// And let me check the P23 case more carefully  
var hole3 = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
var comm3 = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'8',suit:'h'},{rank:'5',suit:'c'},{rank:'2',suit:'c'}];
var result3 = handClassify(hole3, comm3);
console.log('Flush on paired board (4 spades) → ' + result3.name + ' ' + result3.desc);
// 4 spades only = flush draw, not made flush! Need to add another spade

var hole4 = [{rank:'A',suit:'s'},{rank:'K',suit:'s'}];
var comm4 = [{rank:'Q',suit:'s'},{rank:'8',suit:'s'},{rank:'5',suit:'s'},{rank:'8',suit:'h'},{rank:'2',suit:'c'}];
var result4 = handClassify(hole4, comm4);
console.log('Flush made on paired board (5 spades+pair) → ' + result4.name + ' ' + result4.desc);

// Now the real P21: set on flush-made board
var hole5 = [{rank:'7',suit:'h'},{rank:'7',suit:'d'}];
var comm5 = [{rank:'A',suit:'s'},{rank:'7',suit:'s'},{rank:'2',suit:'s'},{rank:'8',suit:'s'},{rank:'3',suit:'c'}];
// A♠7♠2♠8♠ + 7♥7♦ = set of 7s, but 4 spades on board = flush possible for opponent
var cards5 = [].concat(hole5.filter(function(c){return c;}),comm5.filter(function(c){return c;}));
var flushSuits5 = {};
cards5.forEach(function(c){if(c)flushSuits5[c.suit]=(flushSuits5[c.suit]||0)+1;});
console.log('P21 board flushSuits:', JSON.stringify(flushSuits5));
// Only 4 spades total (A,7,2,8) - not a made flush. But opponent could have any 2 spades
// The key issue: handClassify only checks if hero has a flush (needs 5 of same suit)
// It doesn't downgrade set to STRONG when the board has 4+ of same suit
var result5 = handClassify(hole5, comm5);
console.log('Set on 4-flush board → ' + result5.name + ' ' + result5.desc);

