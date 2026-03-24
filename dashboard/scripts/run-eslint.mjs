import { ESLint } from 'eslint';

const args = process.argv.slice(2);
const fix = args.includes('--fix');
const targets = args.filter((arg) => !arg.startsWith('--'));

const eslint = new ESLint({ fix });
const results = await eslint.lintFiles(targets.length > 0 ? targets : ['src']);

if (fix) {
  await ESLint.outputFixes(results);
}

let errorCount = 0;
let warningCount = 0;

for (const result of results) {
  errorCount += result.errorCount;
  warningCount += result.warningCount;

  if (result.messages.length === 0) {
    continue;
  }

  console.log(`\n${result.filePath}`);
  for (const message of result.messages) {
    const severity = message.severity === 2 ? 'error' : 'warning';
    const location = `${message.line}:${message.column}`;
    const ruleId = message.ruleId ?? 'unknown';
    console.log(`  ${location}  ${severity}  ${message.message}  ${ruleId}`);
  }
}

const totalProblems = errorCount + warningCount;
if (totalProblems > 0) {
  console.log(`\n✖ ${totalProblems} problems (${errorCount} errors, ${warningCount} warnings)`);
}

process.exit(errorCount > 0 ? 1 : 0);
