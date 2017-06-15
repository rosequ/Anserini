package io.anserini.qa.ranking;

import io.anserini.embeddings.WordEmbeddingDictionary;
import org.kohsuke.args4j.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.Random;
import org.nd4j.linalg.cpu.nativecpu.rng.CpuNativeRandom;
import org.nd4j.linalg.factory.Nd4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class bridge {
  private int vocabSize;
  private int vectorDimension;
  private Map<Integer, String> vocabDictionary;
  private INDArray unknownVector;
  private WordEmbeddingDictionary wordEmbeddingDictionary;

  public static final class Args {
    // required arguments
    @Option(name = "-index", metaVar = "[Path]", required = true, usage = "index path")
    public String index;

    @Option(name = "-w2vCache", metaVar = "[Path]", required = true, usage = "Word embedding cache file")
    public String w2vCacheFile;

    @Option(name = "-model", metaVar = "[Path]", required = true, usage = "model path")
    public String model;

    @Option(name = "-dataset", metaVar = "[Path]", required = true, usage = "path of the TrecQA dataset folder")
    public String dataset;

    @Option(name = "-output", metaVar = "[Path]", required = true, usage = "output file path")
    public String output = "run";
  }

  public bridge(String index, String w2vCache, String model) throws IOException {
//    ToDo: initialize torch seed
//    ToDo: load model

    this.vocabDictionary = new HashMap();
    wordEmbeddingDictionary = new WordEmbeddingDictionary(index);
    preloadCachedEmbeddings(w2vCache);

  }

  public void preloadCachedEmbeddings(String w2vCache) throws IOException {
    List<String> lines = Files.readAllLines(Paths.get(w2vCache + ".dimensions"));
    String[] sizeDimension = lines.get(0).trim().split("\\t");
    this.vocabSize = Integer.parseInt(sizeDimension[0]);
    this.vectorDimension = Integer.parseInt(sizeDimension[1]);

//    ToDo: initialize W
    try(BufferedReader br = new BufferedReader(new FileReader(w2vCache + ".vocab"))) {
      int i = 0;
      String line = br.readLine();

      while (line != null) {
        line = br.readLine();
        vocabDictionary.put(i, line.trim());
        i++;
      }
      Random rng = new CpuNativeRandom();
      unknownVector = Nd4j.rand(vocabSize, 1, -0.25, 0.25, rng);
    }
  }


  public List<INDArray> makeInputMatrix(String sentence) throws IOException {
    String[] terms = sentence.trim().split("\\t");
    String[] reducedTerms = Arrays.copyOfRange(terms, 0, 60);
    List<INDArray> sentenceEmbedding = new ArrayList<>();

    for (String term : reducedTerms) {
      if (vocabDictionary.keySet().contains(term)) {
        sentenceEmbedding.add(Nd4j.create(wordEmbeddingDictionary.getEmbeddingVector(term)));
      } else {
        sentenceEmbedding.add(unknownVector);
      }
    }
    return sentenceEmbedding;
  }


  public Map<String, Double> rerankCandidates(String question, List<String> answers, String index) throws Exception {
    Map<String, Double> sentScore = new HashMap<>();
    prepareFeature pF = new prepareFeature(index);

    for (String answer : answers) {
      double overlap = pF.computeOverlap(question, answer, false);
      double idfOverlap = pF.idfWeightedOverlap(question, answer, false);
      double overlapStopWords = pF.computeOverlap(question, answer, true);
      double idfOverlapStopWords = pF.idfWeightedOverlap(question, answer, true);

      List<Double> externalFeatures = new ArrayList<>();
      externalFeatures.add(overlap);
      externalFeatures.add(idfOverlap);
      externalFeatures.add(overlapStopWords);
      externalFeatures.add(idfOverlapStopWords);

      // No batching for now:

      List<INDArray> questionEmbedding = makeInputMatrix(question);
      List<INDArray> answerEmbedding = makeInputMatrix(question);
      // Todo: @michael, following code in Java
      // pred = self.model(questionEmbeeding, answerEmbedding, externalFeatures)
      // pred = torch.exp(pred)
      double pred = 0;
      sentScore.put(answer, pred);
    }

    return sentScore;
  }


  public static void main(String[] args) throws Exception {
    bridge.Args bridgeArgs = new bridge.Args();
    CmdLineParser parser = new CmdLineParser(bridgeArgs, ParserProperties.defaults().withUsageWidth(90));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: "+ bridge.class.getSimpleName() +
              parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }
    bridge br = new bridge(bridgeArgs.index, bridgeArgs.w2vCacheFile, bridgeArgs.model);

    String[] config = {"raw_dev", "raw_test"};

    for (String split : config) {
      BufferedReader questionFile = new BufferedReader(new FileReader(bridgeArgs.dataset + "/a.toks"));
      BufferedReader answerFile = new BufferedReader(new FileReader(bridgeArgs.dataset + "/b.toks"));
      BufferedReader idFile = new BufferedReader(new FileReader(bridgeArgs.dataset + "/id.txt"));

      BufferedWriter outputFile = new BufferedWriter(new FileWriter(bridgeArgs.output + ".TrecQA." + split + ".txt"));

      String old_id = "0";
      String previousQuestion = "";
      List<String> answerList = new ArrayList<>();

      while (true) {
        String question = questionFile.readLine();
        String answer = answerFile.readLine();
        String id = idFile.readLine();

        if (question == null || answer == null || id == null) {
          break;
        }

        // collect all the answers correponding to an id and rerank
        if (!old_id.equals(id) && !old_id.equals("0")) {
          Map<String, Double> sentenceScore = br.rerankCandidates(previousQuestion, answerList, bridgeArgs.index);

          // 32.1 0 1 0 0.13309887051582336 smmodel
          int i = 0;
          for (Map.Entry<String, Double> cand : sentenceScore.entrySet()) {
            outputFile.write(old_id + " Q0 " + i + " 0 " + cand.getValue() + " smmodel\n");
            i++;
          }
          answerList.clear();
        }

        previousQuestion = question;
        answerList.add(answer);
        old_id = id;
      }
      outputFile.close();
    }
  }

}
