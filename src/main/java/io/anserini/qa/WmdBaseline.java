/**
 * Created by royalsequeira on 2017-05-26.
 */
/**
 * Anserini: An information retrieval toolkit built on Lucene
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.qa;

import io.anserini.embeddings.TermNotFoundException;
import io.anserini.embeddings.WordEmbeddingDictionary;
import io.anserini.index.generator.LuceneDocumentGenerator;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;
import org.bytedeco.javacpp.presets.opencv_core;
import org.kohsuke.args4j.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;


public class WmdBaseline {

  public static class Args {
    // required arguments
    @Option(name = "-index", metaVar = "[path]", required = true, usage = "Lucene index")
    public String index;

    @Option(name = "-config", metaVar = "[string]", required = true, usage = "type of the dataset")
    public String config;

    @Option(name = "-output", metaVar = "[path]", required = true, usage = "path of the file to be created")
    public String output;

    // optional arguments
    @Option(name = "-analyze", usage = "passage scores")
    public boolean analyze = false;

    @Option(name = "-split", usage = "passage scores")
    public boolean split = false;

    @Option(name = "-idfIndex", metaVar = "[path]", usage = "Lucene index")
    public String idfIndex = "";

  }

  private final WordEmbeddingDictionary wmdDictionary;
  private FSDirectory directory = null;
  private DirectoryReader reader = null;
  private final List<String> stopWords;

  public static final String FIELD_BODY = "contents";

  public WmdBaseline(WmdBaseline.Args args) throws Exception {
    if (!args.idfIndex.isEmpty()) {
      this.directory = FSDirectory.open(new File(args.idfIndex).toPath());
      this.reader = DirectoryReader.open(directory);
    }

    this.wmdDictionary = new WordEmbeddingDictionary(args.index);
    stopWords = new ArrayList<>();

    //Get file from resources folder
    InputStream is = getClass().getResourceAsStream("/io/anserini/qa/english-stoplist.txt");
    BufferedReader bRdr = new BufferedReader(new InputStreamReader(is));
    String line;
    while ((line = bRdr.readLine()) != null) {
      if (!line.startsWith("#")) {
        stopWords.add(line);
      }
    }
  }

  public double distance(float[] leftVector, float[] rightVector) {
    double sum = 0.0;
    for (int i = 0; i < leftVector.length; i++) {
      sum += Math.pow(leftVector[i] - rightVector[i], 2);
    }
    return  Math.sqrt(sum);
  }

  public double getTermIDF(String term) throws ParseException {
    Analyzer analyzer = new EnglishAnalyzer(CharArraySet.EMPTY_SET);
    QueryParser qp = new QueryParser(FIELD_BODY, analyzer);
    ClassicSimilarity similarity = new ClassicSimilarity();

    String esTerm = qp.escape(term);
    double termIDF = 0.0;
    try {
      TermQuery q = (TermQuery) qp.parse(esTerm);
      Term t = q.getTerm();
      termIDF = similarity.idf(reader.docFreq(t), reader.numDocs());

    //System.out.println(term + '\t' + esTerm + '\t' + q + '\t' + t + '\t' + termIDF);
    } catch (Exception e) {
      System.err.println("Exception in fetching IDF(" + term + "): " + e.toString());
      return 0;
    }
    return termIDF;
  }

  public double calcWmd(String query, String answer, boolean analyze, boolean split) throws ParseException, IOException {
    query = query.replace("-", " ");
    answer = answer.replace("-", " ");
    Analyzer sa;
    Analyzer sa2;

    if (analyze) {
      sa = new StandardAnalyzer(StopFilter.makeStopSet(stopWords));
      sa2 = new StandardAnalyzer(StopFilter.makeStopSet(stopWords));
    } else {
      sa = new WhitespaceAnalyzer();
      sa2 = new WhitespaceAnalyzer();
    }

    TokenStream tokenStream = sa.tokenStream("contents", new StringReader(query));
    CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
    tokenStream.reset();

    HashSet<String> questionTerms = new HashSet<>();
    HashSet<String> candidateTerms = new HashSet<>();

    while (tokenStream.incrementToken()) {
      String thisTerm = charTermAttribute.toString();
      questionTerms.add(thisTerm);
    }

    double wmd = 0.0;
    double totalIDF = 0.0;
    TokenStream candTokenStream = sa2.tokenStream("contents", new StringReader(answer));
    charTermAttribute = candTokenStream.addAttribute(CharTermAttribute.class);
    candTokenStream.reset();

    while (candTokenStream.incrementToken()) {
      String thisTerm = charTermAttribute.toString();
      candidateTerms.add(thisTerm);
    }

    for(String qTerm : questionTerms) {
      double minWMD = Double.MAX_VALUE;
      double idf = 1.0;
      for (String candTerm : candidateTerms) {
        try {
          double thisWMD = distance(wmdDictionary.getEmbeddingVector(qTerm), wmdDictionary.getEmbeddingVector(candTerm));
          if (minWMD > thisWMD) {
            minWMD = thisWMD;
          }
        } catch (TermNotFoundException e) {
          double thisWMD = 0.0;
          String missingTerm = e.getMessage();
          if (qTerm.equals(missingTerm)) {
            if (qTerm.equals(candTerm)) {
              thisWMD = 0.0;
              minWMD = thisWMD;
            } else {
              try {
                thisWMD = distance(wmdDictionary.getEmbeddingVector("unk"), wmdDictionary.getEmbeddingVector(candTerm));
                if (minWMD > thisWMD) {
                  minWMD = thisWMD;
                }
              } catch (TermNotFoundException e1) {

              }
            }
          } else {
            continue;
          }
        } catch (IOException e) {
        }
      }

      if (minWMD != Double.MAX_VALUE) {
//        idf = getTermIDF(qTerm);
//        wmd += (minWMD * idf);
//        totalIDF += idf;
        wmd += minWMD;
      }
    }
    return -wmd;
  }

  public  void writeToFile(WmdBaseline.Args args) throws IOException, ParseException {
    BufferedReader questionFile = new BufferedReader(new FileReader(args.config + "/a.toks"));
    BufferedReader answerFile = new BufferedReader(new FileReader(args.config + "/b.toks"));
    BufferedReader wmdile = new BufferedReader(new FileReader(args.config + "/id.txt"));

    BufferedWriter outputFile = new BufferedWriter(new FileWriter(args.output));
    int i = 0;

    String old_id = "0";
    int count = 0;
    while (true) {
      String question = questionFile.readLine();
      String answer = answerFile.readLine();
      String id = wmdile.readLine();

      if (question == null || answer == null || id == null) {
        break;
      }

      // we need new lines here
      if (args.config.contains("WikiQA") && !old_id.equals(id)) {
        old_id = id;
        i = 0;
      }

      // 32.1 0 0 0 0.6212325096130371 smmodel
      // 32.1 0 1 0 0.13309887051582336 smmodel
        outputFile.write(id + " 0 " + i + " 0 " + calcWmd(question, answer, args.analyze, args.split) + " wmdbaseline\n");
        i++;
    }
    outputFile.close();

  }

  public static void main(String[] args) throws Exception {
    WmdBaseline.Args qaArgs = new WmdBaseline.Args();
    CmdLineParser parser = new CmdLineParser(qaArgs, ParserProperties.defaults().withUsageWidth(90));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: WMDScorer" + parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }

    WmdBaseline g = new WmdBaseline(qaArgs);
    g.writeToFile(qaArgs);
  }
}

