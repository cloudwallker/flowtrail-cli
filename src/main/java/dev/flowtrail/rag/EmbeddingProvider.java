package dev.flowtrail.rag;

public interface EmbeddingProvider {
  String modelId();

  int dimensions();

  double[] embed(String text) throws Exception;
}
