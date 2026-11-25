/* 背词工具模糊搜索：多字段加权 + 前缀/子串/子序列/小编辑距离。
   纯函数，便于 Node 回归；不依赖 DOM。 */
(function (root) {
  'use strict';

  function normalize(value) {
    return String(value == null ? '' : value)
      .normalize('NFKD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function compact(value) {
    return normalize(value).replace(/\s+/g, '');
  }

  function levenshtein(a, b, limit) {
    if (Math.abs(a.length - b.length) > limit) return limit + 1;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      let rowMin = i;
      for (let j = 1; j <= b.length; j++) {
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
        if (cur[j] < rowMin) rowMin = cur[j];
      }
      if (rowMin > limit) return limit + 1;
      prev = cur;
    }
    return prev[b.length];
  }

  function subsequenceScore(text, query) {
    let ti = 0, gaps = 0, last = -1;
    for (const ch of query) {
      const found = text.indexOf(ch, ti);
      if (found < 0) return 0;
      if (last >= 0) gaps += found - last - 1;
      last = found;
      ti = found + 1;
    }
    return Math.max(0, 420 - gaps * 8) * (query.length / Math.max(text.length, 1));
  }

  function fieldScore(text, query, allowTypo, allowFuzzy) {
    const t = normalize(text), q = normalize(query);
    if (!t || !q) return 0;
    if (t === q) return 1000;
    if (t.startsWith(q)) return 880 - Math.min(120, t.length - q.length);
    const boundary = ' ' + t;
    if (boundary.includes(' ' + q)) return 800 - Math.min(120, t.indexOf(q));
    const shortLatin = /^[a-z]+$/.test(q) && q.length < 4;
    if (!shortLatin && t.includes(q)) return 680 - Math.min(160, t.indexOf(q));
    const tc = compact(t), qc = compact(q);
    if (!shortLatin && qc.length >= 2 && tc.includes(qc)) return 620 - Math.min(120, tc.indexOf(qc));
    if (allowFuzzy && qc.length >= 4 && tc.length <= qc.length * 3 + 2) {
      const sub = subsequenceScore(tc, qc);
      if (sub > 0) return Math.min(420, sub);
    }
    if (allowTypo && qc.length >= 3) {
      const limit = qc.length >= 5 ? 2 : 1;
      const dist = levenshtein(tc, qc, limit);
      if (dist <= limit) return 500 - dist * 120;
    }
    return 0;
  }

  function tokenScore(text, token, allowTypo, allowFuzzy) {
    const parts = normalize(text).split(' ').filter(Boolean);
    let best = fieldScore(text, token, allowTypo, allowFuzzy);
    for (const part of parts) best = Math.max(best, fieldScore(part, token, allowTypo, allowFuzzy));
    return best;
  }

  function hasCjk(value) {
    return /[\u3400-\u9fff]/u.test(String(value == null ? '' : value));
  }

  function scoreRow(row, query, explanation) {
    const q = String(query == null ? '' : query).trim();
    if (!q) return 0;
    const tokens = normalize(q).split(' ').filter(Boolean);
    if (!tokens.length) return 0;
    const word = row && row.word ? row.word : {};
    const wordFields = [[word.w, 1.00, true, true], [word.lemma, 0.95, true, true]];
    const meaningFields = [
      [word.cn, 0.90, false, false], [word.en, 0.86, false, false],
      [explanation && explanation.cn, 0.84, false, false],
      [explanation && explanation.en, 0.80, false, false]
    ];
    let total = 0;
    for (const token of tokens) {
      // 英文查单词/原形，中文查中文释义；绝不拿英文词去命中别的词的英文释义。
      const fields = hasCjk(token) ? meaningFields : wordFields;
      let best = 0;
      for (const [value, weight, typo, fuzzy] of fields) {
        best = Math.max(best, tokenScore(value, token, typo, fuzzy) * weight);
      }
      if (best <= 0) return 0;
      // 例句只能给已命中的词条加权，不能单独把无关词条带进结果。
      let exampleBoost = 0;
      for (const example of (word.examples || [])) {
        exampleBoost = Math.max(exampleBoost, fieldScore(example, token, false, false));
      }
      total += best + Math.min(25, exampleBoost * 0.03);
    }
    return Math.round(total / tokens.length);
  }

  function rank(rows, query, explanationOf) {
    const q = String(query == null ? '' : query).trim();
    if (!q) return rows.slice();
    return rows.map((row, index) => ({
      row, index, score: scoreRow(row, q, explanationOf ? explanationOf(row) : row.word && row.word.explanation)
    })).filter(item => item.score > 0)
      .sort((a, b) => b.score - a.score || a.index - b.index)
      .map(item => item.row);
  }

  root.VocabSearch = { normalize, compact, hasCjk, fieldScore, scoreRow, rank, levenshtein };
})(typeof window !== 'undefined' ? window : globalThis);
