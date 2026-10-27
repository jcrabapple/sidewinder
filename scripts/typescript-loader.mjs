/**
 * Resolve Vite-style extensionless relative imports in Node's strip-types
 * tests. Production code keeps its existing import style; no runtime loader
 * or dependency is needed.
 */
export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context)
  } catch (error) {
    if (
      error?.code !== 'ERR_MODULE_NOT_FOUND' ||
      (!specifier.startsWith('.') && !specifier.startsWith('/'))
    ) {
      throw error
    }
    for (const suffix of ['.ts', '.tsx', '/index.ts', '/index.tsx']) {
      try {
        return await nextResolve(specifier + suffix, context)
      } catch (candidateError) {
        if (candidateError?.code !== 'ERR_MODULE_NOT_FOUND') throw candidateError
      }
    }
    throw error
  }
}
