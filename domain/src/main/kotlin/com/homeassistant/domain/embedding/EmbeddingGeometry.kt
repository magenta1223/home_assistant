package com.homeassistant.domain.embedding

/** Comparisons between vectors of the shared embedding space. */
object EmbeddingGeometry {
    /** The cosine similarity of two vectors of the same dimension. */
    fun cosine(first: EmbeddingVector, second: EmbeddingVector): Double {
        require(first.dimension == second.dimension) { "vector dimension mismatch" }
        return first.values().zip(second.values()).sumOf { (a, b) -> a * b }.coerceIn(-1.0, 1.0)
    }

    /** The [k] ids whose vectors are nearest to [center], nearest first with their cosine; an equal cosine keeps the smaller id. */
    fun nearest(center: EmbeddingVector, vectors: Map<Int, EmbeddingVector>, k: Int): List<Pair<Int, Double>> =
        vectors.map { (id, vector) -> id to cosine(center, vector) }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .take(k)

    /** Only the direction of the equal-weight mean is needed for cosine comparisons. */
    fun centroid(vectors: Collection<EmbeddingVector>): EmbeddingVector {
        require(vectors.isNotEmpty())
        val dimension = vectors.first().dimension
        require(vectors.all { it.dimension == dimension }) { "vector dimension mismatch" }
        val rows = vectors.map { it.values() }
        return EmbeddingVector.normalized(List(dimension) { axis -> rows.sumOf { it[axis] / rows.size } })
    }
}
