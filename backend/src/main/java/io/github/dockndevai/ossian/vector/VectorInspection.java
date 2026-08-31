package io.github.dockndevai.ossian.vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a set of high-dimensional embeddings into something a human can look at.
 *
 * <p>Embeddings are 768 numbers; nobody can read that. A two-component PCA projection is the
 * cheapest honest summary: it keeps the directions of greatest variance, so chunks that the
 * retriever considers similar land near each other on screen. It is lossy by construction —
 * the projection is a shadow of the real space, and points that look close in two dimensions
 * are not necessarily close in 768. The explained-variance figure returned alongside says how
 * much of a shadow it is.
 */
final class VectorInspection {

	private VectorInspection() {
	}

	/** A single point of the 2-D projection, with the fraction of variance the axes explain. */
	record Projection(List<double[]> points, double explainedVariance) {
	}

	/**
	 * Projects rows onto their first two principal components.
	 *
	 * <p>Uses power iteration rather than forming the 768x768 covariance matrix. With a handful
	 * of documents the corpus is far smaller than the dimensionality, so iterating against the
	 * data directly is both faster and better conditioned.
	 */
	static Projection project(List<float[]> rows) {
		int n = rows.size();
		if (n == 0) {
			return new Projection(List.of(), 0);
		}
		int dim = rows.get(0).length;

		double[][] x = new double[n][dim];
		double[] mean = new double[dim];
		for (int i = 0; i < n; i++) {
			float[] row = rows.get(i);
			for (int d = 0; d < dim; d++) {
				x[i][d] = row[d];
				mean[d] += row[d];
			}
		}
		for (int d = 0; d < dim; d++) {
			mean[d] /= n;
		}
		for (int i = 0; i < n; i++) {
			for (int d = 0; d < dim; d++) {
				x[i][d] -= mean[d];
			}
		}

		double total = 0;
		for (double[] row : x) {
			for (double v : row) {
				total += v * v;
			}
		}

		// A single point, or identical points, has no direction of variance to find.
		if (n < 2 || total == 0) {
			List<double[]> flat = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				flat.add(new double[] { 0, 0 });
			}
			return new Projection(flat, 0);
		}

		double[] pc1 = component(x, dim, List.of());
		double[] pc2 = component(x, dim, List.of(pc1));

		List<double[]> points = new ArrayList<>(n);
		double kept = 0;
		for (double[] row : x) {
			double a = dot(row, pc1);
			double b = dot(row, pc2);
			points.add(new double[] { a, b });
			kept += a * a + b * b;
		}
		return new Projection(points, kept / total);
	}

	/** One principal direction, orthogonal to any already found. */
	private static double[] component(double[][] x, int dim, List<double[]> against) {
		double[] v = new double[dim];
		// Deterministic start: a fixed pseudo-random vector, so the same corpus always projects
		// the same way and the plot does not reshuffle between page loads.
		long seed = 42;
		for (int d = 0; d < dim; d++) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			v[d] = ((seed >>> 11) / (double) (1L << 53)) - 0.5;
		}
		orthogonalise(v, against);
		normalise(v);

		double[] next = new double[dim];
		for (int iter = 0; iter < 64; iter++) {
			// next = X^T (X v), i.e. apply the covariance without ever building it.
			java.util.Arrays.fill(next, 0);
			for (double[] row : x) {
				double p = dot(row, v);
				for (int d = 0; d < dim; d++) {
					next[d] += p * row[d];
				}
			}
			orthogonalise(next, against);
			if (!normalise(next)) {
				break;
			}
			double shift = 0;
			for (int d = 0; d < dim; d++) {
				shift += Math.abs(next[d] - v[d]);
			}
			System.arraycopy(next, 0, v, 0, dim);
			if (shift < 1e-9) {
				break;
			}
		}
		return v;
	}

	private static void orthogonalise(double[] v, List<double[]> against) {
		for (double[] u : against) {
			double p = dot(v, u);
			for (int d = 0; d < v.length; d++) {
				v[d] -= p * u[d];
			}
		}
	}

	private static boolean normalise(double[] v) {
		double norm = Math.sqrt(dot(v, v));
		if (norm < 1e-12) {
			return false;
		}
		for (int d = 0; d < v.length; d++) {
			v[d] /= norm;
		}
		return true;
	}

	private static double dot(double[] a, double[] b) {
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}

	/** Parses pgvector's text form, {@code [0.1,0.2,...]}. */
	static float[] parse(String literal) {
		String body = literal.trim();
		if (body.startsWith("[")) {
			body = body.substring(1, body.length() - 1);
		}
		if (body.isEmpty()) {
			return new float[0];
		}
		String[] parts = body.split(",");
		float[] out = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = Float.parseFloat(parts[i]);
		}
		return out;
	}

	static double norm(float[] v) {
		double sum = 0;
		for (float f : v) {
			sum += (double) f * f;
		}
		return Math.sqrt(sum);
	}

}
