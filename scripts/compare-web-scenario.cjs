// Run from rosebud-android: node scripts/compare-web-scenario.cjs [path-to-rosebud-app]
// Evaluates the original TypeScript, never a second transcription of its formulas.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const webRoot = path.resolve(process.argv[2] || '../rosebud-app');
const ts = require(path.join(webRoot, 'node_modules/typescript'));
const input = fs.readFileSync(path.join(webRoot, 'app/data/scenarios.ts'), 'utf8');
const page = fs.readFileSync(path.join(webRoot, 'app/page.tsx'), 'utf8');
const processSource = page.slice(page.indexOf('  const processScenario = (elapsed: number) => {'), page.indexOf('\n  useEffect(() => {\n    intervalRef', page.indexOf('  const processScenario = (elapsed: number) => {')));
let seed = 0x10019;
let state = seed;
const math = Object.create(Math);
math.random = () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
const context = {exports: {}, Math: math};
vm.createContext(context);
vm.runInContext(ts.transpileModule(input, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText, context);
context.SCENARIOS = context.exports.SCENARIOS;
context.currentScenarioId = 'boiling_frog';
vm.runInContext(ts.transpileModule(processSource + '\nexports.processScenario = processScenario;', {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText, context);
// 0.1-second run plus exact boundary/last-stage branch probes and later loops.
const times = new Set(Array.from({length: 19201}, (_, i) => i / 10));
for (const t of [2, 2.6, 360, 540, 720, 900, 1020, 1030, 1040, 1050, 1140, 1920, 2700, 1000000]) {
  for (const delta of [-0.001, 0, 0.001]) if (t + delta >= 0) times.add(t + delta);
}
const rows = [...times].sort((a,b) => a-b).map(t => { const v = context.exports.processScenario(t); return [t, v.stageIndex, v.vib, v.lick]; });
const fixture = {seed, scenarios_sha256: crypto.createHash('sha256').update(input).digest('hex'), page_sha256: crypto.createHash('sha256').update(page).digest('hex'), rows};
fs.writeFileSync(path.resolve('app/src/test/resources/web-boiling-frog.json.gz'), zlib.gzipSync(JSON.stringify(fixture), {level: 9}));
console.log(`Generated ${rows.length} reference frames directly from Web sources; seed ${seed}`);
