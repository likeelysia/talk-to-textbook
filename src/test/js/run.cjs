const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const scripts = ['frontend_probe.cjs', 'image_finally_probe.cjs', 'submit_finally_probe.cjs'];
const reports = ['frontend-results.json', 'image-finally-results.json', 'submit-finally-results.json'];
let total = 0;
let failed = 0;

for (const script of scripts) {
    execFileSync(process.execPath, [path.join(__dirname, script)], { stdio: 'inherit' });
}
for (const report of reports) {
    const data = JSON.parse(fs.readFileSync(path.join(__dirname, report), 'utf8'));
    const results = data.results || [data];
    for (const result of results) {
        total++;
        if (result.passed !== true) {
            failed++;
            console.error('FAILED:', result.test || JSON.stringify(result));
        }
    }
}
console.log(`frontend identity: total=${total} failed=${failed} passed=${total - failed}`);
process.exit(failed === 0 ? 0 : 1);
