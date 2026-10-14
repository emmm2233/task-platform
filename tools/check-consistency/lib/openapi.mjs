import { readFileSync, existsSync } from 'node:fs';
import { parse as parseYaml } from 'yaml';

/**
 * 解析 OpenAPI 文档，抽出「schema 名 → 属性名列表」与「所有路径」。
 * 只需要这两样东西做一致性比对。
 */
export function parseOpenApi(file) {
  if (!existsSync(file)) {
    return { schemas: new Map(), paths: [], raw: '', text: '' };
  }
  const text = readFileSync(file, 'utf8').replace(/^\uFEFF/, '');

  let doc;
  try {
    doc = parseYaml(text);
  } catch (e) {
    throw new Error(`OpenAPI 解析失败：${e.message}`);
  }
  if (!doc || typeof doc !== 'object') {
    throw new Error('OpenAPI 解析结果为空');
  }

  const schemas = new Map();
  for (const [name, schema] of Object.entries(doc.components?.schemas ?? {})) {
    const props = new Set();
    collectProps(schema, props, doc.components?.schemas ?? {}, new Set([name]));
    schemas.set(name, { name, props, schema });
  }

  const paths = Object.keys(doc.paths ?? {});

  return { schemas, paths, raw: doc, text };
}

/**
 * 递归收集 properties 名（含 allOf/oneOf/anyOf 与 $ref 展开）。
 * ⛔ 深度受限：$ref 可能出现环（如 Task → TaskDetail → Task），用 seen 阻断。
 */
function collectProps(schema, out, allSchemas, seen) {
  if (!schema || typeof schema !== 'object') {
    return;
  }
  if (schema.$ref) {
    const refName = schema.$ref.split('/').pop();
    if (seen.has(refName)) {
      return;
    }
    seen.add(refName);
    collectProps(allSchemas[refName], out, allSchemas, seen);
    return;
  }
  for (const key of ['allOf', 'oneOf', 'anyOf']) {
    for (const sub of schema[key] ?? []) {
      collectProps(sub, out, allSchemas, seen);
    }
  }
  for (const name of Object.keys(schema.properties ?? {})) {
    out.add(name);
  }
  if (schema.items) {
    collectProps(schema.items, out, allSchemas, seen);
  }
}
