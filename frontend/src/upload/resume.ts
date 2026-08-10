export function missingChunkIndexes(totalChunks: number, uploadedChunks: Iterable<number>): number[] {
  const uploaded = new Set(uploadedChunks)
  return Array.from({ length: totalChunks }, (_, index) => index).filter((index) => !uploaded.has(index))
}
