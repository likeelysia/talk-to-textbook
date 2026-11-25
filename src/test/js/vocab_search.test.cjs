const assert = require('node:assert/strict');
const path = require('node:path');
require(path.resolve(__dirname, '../../main/resources/static/vocab-search.js'));
const V = globalThis.VocabSearch;
const rows = [
  {word: {w: 'exact', lemma: 'exact', cn: '精确', en: 'exact match'}},
  {word: {w: 'exactly', lemma: 'exact', cn: '确切地', en: 'in an exact manner'}},
  {word: {w: 'inexact', lemma: 'inexact', cn: '不精确的', en: 'not exact'}},
  {word: {w: 'example', lemma: 'example', cn: '例子', en: 'a case'}},
  {word: {w: 'social animal', lemma: 'social animal', cn: '社会性动物', en: 'social animal'}},
  {word: {w: 'banana', lemma: 'banana', cn: '香蕉', en: 'a fruit', examples: ['The cat sat on the mat.']}},
  {word: {w: 'cat', lemma: 'cat', cn: '猫', en: 'a small animal'}},
  {word: {w: 'security', lemma: 'security', cn: '安全', en: 'freedom from danger'}},
  {word: {w: 'freedom', lemma: 'freedom', cn: '自由', en: 'the power to act'}},
  {word: {w: 'communicate', lemma: 'communicate', cn: '交流', en: 'share information'}},
  {word: {w: 'café', lemma: 'café', cn: '咖啡馆', en: 'coffee house'}}
];
const q = query => V.rank(rows, query, r => r.word).map(r => r.word.w);

assert.deepEqual(q('exact').slice(0, 3), ['exact', 'exactly', 'inexact']);
assert.equal(q('exact')[0], 'exact');
assert.ok(q('exatc').includes('exact'), 'small typo should still find exact');
assert.ok(q('socail').includes('social animal'), 'small typo should still find phrase');
assert.ok(q('animal social').includes('social animal'), 'multi-token order should not matter');
assert.ok(q('咖啡馆').includes('café'), 'Chinese gloss search');
assert.deepEqual(q('zzzz-not-found'), []);
assert.ok(q('cafe').includes('café'), 'diacritics should fold');
assert.ok(q('cat').includes('cat'), 'real word should match');
assert.ok(!q('cat').includes('banana'), 'hidden example must not qualify an unrelated word');
assert.ok(!q('cat').includes('communicate'), 'scattered letters must not match a long word');
assert.deepEqual(q('freedom'), ['freedom'], 'English query must not match another word\'s English definition');
assert.ok(q('安全').includes('security'), 'Chinese query should match Chinese meaning');

const ranked = q('exact');
assert.equal(ranked[0], 'exact');
console.log('vocab fuzzy search: 14 assertions passed');
