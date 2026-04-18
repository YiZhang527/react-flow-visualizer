import fs from 'fs';
import path from 'path';
import { parse } from '@babel/parser';
import traverseModule from '@babel/traverse';
const traverse = traverseModule.default;

const EXT = new Set(['js', 'jsx', 'mjs', 'cjs', 'ts', 'tsx']);
const SKIP = new Set([
  'node_modules', '.git', '.svn', 'dist', 'build', '.next', '.nuxt',
  '__pycache__', '.venv', 'venv', '.idea', '.vscode', 'coverage', '.cache'
]);

function normalizePath(p) {
  const parts = [];
  for (const s of p.split('/')) {
    if (s === '.' || s === '') continue;
    if (s === '..') { if (parts.length) parts.pop(); }
    else parts.push(s);
  }
  return parts.join('/');
}

function resolveRel(fromRel, importPath, relSet) {
  const fromDir = fromRel.includes('/') ? fromRel.slice(0, fromRel.lastIndexOf('/')) : '';
  const base = fromDir === ''
    ? importPath.replace(/^\.\//, '')
    : `${fromDir}/${importPath.replace(/^\.\//, '')}`;
  const norm = normalizePath(base);
  for (const ext of ['', '.js', '.jsx', '.ts', '.tsx', '.mjs']) {
    const r = norm + ext;
    if (relSet.has(r)) return r;
  }
  for (const idx of ['/index.js', '/index.jsx', '/index.ts', '/index.tsx']) {
    const r = norm + idx;
    if (relSet.has(r)) return r;
  }
  return null;
}

function jsxName(node) {
  if (!node) return null;
  if (node.type === 'JSXIdentifier') return node.name;
  if (node.type === 'JSXMemberExpression') return jsxName(node.object);
  return null;
}

function walkFiles(rootAbs) {
  const out = [];
  function walk(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      if (SKIP.has(ent.name)) continue;
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) walk(full);
      else {
        const ext = path.extname(ent.name).slice(1).toLowerCase();
        if (EXT.has(ext)) out.push(full);
      }
    }
  }
  walk(rootAbs);
  return out;
}

function toRel(absFile, rootAbs) {
  return path.relative(rootAbs, absFile).split(path.sep).join('/');
}

function main() {
  const rootAbs = path.resolve(process.argv[2] || '.');
  if (!fs.existsSync(rootAbs)) {
    console.error(JSON.stringify({ error: 'root not found', rootAbs }));
    process.exit(1);
  }

  const files = walkFiles(rootAbs);
  const relSet = new Set(files.map(f => toRel(f, rootAbs)));

  const fileSummaries = {};
  const edges = [];
  const edgeKeys = new Set();

  function addEdge(source, target, kind) {
    const k = `${source}|${target}|${kind}`;
    if (edgeKeys.has(k)) return;
    edgeKeys.add(k);
    edges.push({ source, target, kind });
  }

  for (const abs of files) {
    const fromRel = toRel(abs, rootAbs);
    let code;
    try {
      code = fs.readFileSync(abs, 'utf8');
    } catch {
      continue;
    }

    let ast;
    try {
      ast = parse(code, {
        sourceType: 'unambiguous',
        plugins: ['jsx', 'typescript']
      });
    } catch {
      continue;
    }

    const imports = Object.create(null);
    const usages = [];
    const passesDown = [];

    traverse(ast, {
      ImportDeclaration(p) {
        const src = p.node.source?.value;
        if (typeof src !== 'string' || !src.startsWith('.')) return;
        const resolved = resolveRel(fromRel, src, relSet);
        if (!resolved) return;
        for (const sp of p.node.specifiers) {
          if (sp.type === 'ImportDefaultSpecifier' || sp.type === 'ImportSpecifier') {
            imports[sp.local.name] = resolved;
          }
        }
      },
      JSXOpeningElement(p) {
        const name = jsxName(p.node.name);
        if (!name || /^[a-z]/.test(name)) return;
        const resolved = imports[name] ?? null;
        const props = [];
        for (const attr of p.node.attributes) {
          if (attr.type !== 'JSXAttribute' || !attr.name) continue;
          const n = attr.name.name;
          if (typeof n !== 'string') continue;
          const kind = /^on[A-Z]/.test(n) ? 'callback' : 'data';
          props.push({ name: n, kind });
        }
        usages.push({ tag: name, resolved, props });
        if (resolved) {
          const toLabel = path.basename(resolved).replace(/\.[^.]+$/, '');
          for (const pr of props) {
            passesDown.push({
              prop: pr.name,
              kind: pr.kind,
              toRel: resolved,
              toLabel,
              toTag: name
            });
          }
        }
        if (resolved) {
          const hasData = props.some(x => x.kind === 'data');
          const hasCb = props.some(x => x.kind === 'callback');
          if (hasData) addEdge(fromRel, resolved, 'props');
          if (hasCb) addEdge(fromRel, resolved, 'callback');
        }
      }
    });

    if (usages.length > 0 || passesDown.length > 0) {
      fileSummaries[fromRel] = { usages, passesDown };
    }
  }

  const out = { version: 1, fileSummaries, edges };
  process.stdout.write(JSON.stringify(out));
}

main();
