import { access } from 'node:fs/promises';
import path from 'node:path';

const binExt = process.platform === 'win32' ? '.cmd' : '';
const requiredFiles = [
  ...[`tsc${binExt}`, `vite${binExt}`].map((name) => path.join('node_modules', '.bin', name)),
  path.join('node_modules', '@types', 'react', 'index.d.ts'),
  path.join('node_modules', '@types', 'react-dom', 'index.d.ts'),
];
const deadline = Date.now() + Number(process.env.BUILD_BIN_WAIT_MS ?? 15_000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function exists(filePath) {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
}

while (true) {
  const missing = [];
  for (const file of requiredFiles) {
    if (!(await exists(file))) {
      missing.push(file);
    }
  }

  if (missing.length === 0) {
    break;
  }

  if (Date.now() >= deadline) {
    console.error(`Build inputs did not appear: ${missing.join(', ')}`);
    process.exit(1);
  }

  await sleep(250);
}
