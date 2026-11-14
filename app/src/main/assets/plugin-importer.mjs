/** Keep the importer in the local npm project so bare package resolution uses its node_modules. */
export async function importOriginalPlugin(specifier) {
  return import(specifier);
}
