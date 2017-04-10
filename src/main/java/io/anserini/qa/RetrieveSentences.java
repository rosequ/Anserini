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

import edu.stanford.nlp.simple.Sentence;
import io.anserini.index.IndexUtils;
import io.anserini.qa.passage.PassageScorer;
import io.anserini.qa.passage.ScoredPassage;
import io.anserini.rerank.ScoredDocuments;
import io.anserini.search.query.QaTopicReader;
import io.anserini.util.AnalyzerUtils;
import net.sourceforge.argparse4j.annotation.Arg;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.*;

import java.io.*;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static io.anserini.index.generator.LuceneDocumentGenerator.FIELD_BODY;
import static io.anserini.index.generator.LuceneDocumentGenerator.FIELD_ID;

public class RetrieveSentences {

  public static class Args {
    // required arguments
    @Option(name = "-index", metaVar = "[path]", required = true, usage = "Lucene index")
    public String index;

    // optional arguments
    @Option(name = "-topics", metaVar = "[file]", usage = "topics file")
    public String topics = "";

    @Option(name = "-query", metaVar = "[string]", usage = "a single query")
    public String query = "";

    @Option(name = "-hits", metaVar = "[number]", usage = "max number of hits to return")
    public int hits = 100;

    @Option(name = "-scorer", metaVar = "[Idf|Wmd]", usage = "passage scores")
    public String scorer;

    @Option(name = "-k", metaVar = "[number]", usage = "top-k passages to be retrieved")
    public int k = 1;

    @Option(name = "-config", metaVar = "[string]", usage = "[dev|train|test]")
    public String config = "dev";
  }

  private final IndexReader reader;
  private final PassageScorer scorer;

  public RetrieveSentences(RetrieveSentences.Args args) throws Exception {
    Path indexPath = Paths.get(args.index);

    if (!Files.exists(indexPath) || !Files.isDirectory(indexPath) || !Files.isReadable(indexPath)) {
      throw new IllegalArgumentException(args.index + " does not exist or is not a directory.");
    }

    this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    Constructor passageClass = Class.forName("io.anserini.qa.passage." + args.scorer + "PassageScorer")
            .getConstructor(String.class, Integer.class);

    scorer = (PassageScorer) passageClass.newInstance(args.index, args.k);
  }

  public Map<String, Float> search(SortedMap<Integer, String> topics, int numHits)
          throws IOException, ParseException {
    IndexSearcher searcher = new IndexSearcher(reader);

    //using BM25 scoring model
    Similarity similarity = new BM25Similarity(0.9f, 0.4f);
    searcher.setSimilarity(similarity);

    EnglishAnalyzer ea = new EnglishAnalyzer();
    QueryParser queryParser = new QueryParser(FIELD_BODY, ea);
    queryParser.setDefaultOperator(QueryParser.Operator.OR);
    Map<String, Float> scoredDocs = new LinkedHashMap<>();

    for (Map.Entry<Integer, String> entry : topics.entrySet()) {
      int qID = entry.getKey();
      String queryString = entry.getValue();
      Query query = AnalyzerUtils.buildBagOfWordsQuery(FIELD_BODY, ea, queryString);

      TopDocs rs = searcher.search(query, numHits);
      ScoreDoc[] hits = rs.scoreDocs;
      ScoredDocuments docs = ScoredDocuments.fromTopDocs(rs, searcher);

      for (int i = 0; i < docs.documents.length; i++) {
        scoredDocs.put(docs.documents[i].getField(FIELD_ID).stringValue(), docs.scores[i]);
      }
    }
    return scoredDocs;
  }

  public void getRankedPassages(Args args) throws Exception {
    Map<String, Float> scoredDocs  = retrieveDocuments(args);
    Map<String, Float> sentencesMap = new LinkedHashMap<>();

    IndexUtils util = new IndexUtils(args.index);

    for (Map.Entry<String, Float> doc : scoredDocs.entrySet()) {
        List<Sentence> sentences = util.getSentDocument(doc.getKey());

        for (Sentence sent : sentences) {
          sentencesMap.put(sent.text(), doc.getValue());
        }
    }

    scorer.score(args.query, sentencesMap);

    List<ScoredPassage> topPassages = scorer.extractTopPassages();
    for (ScoredPassage s: topPassages) {
      System.out.println(s.getSentence() + " " + s.getScore());
    }
  }

  public Map<String, Float> retrieveDocuments(RetrieveSentences.Args args) throws Exception {
    SortedMap<Integer, String> topics = new TreeMap<>();
    if (!args.topics.isEmpty()) {
      QaTopicReader tr = new QaTopicReader(Paths.get(args.topics));
      topics = tr.read();
    } else {
      topics.put(1, args.query);
    }

    Map<String, Float> scoredDocs = search(topics, args.hits);
    return scoredDocs;
  }

  public void getIDFPassages(Args qaArgs) throws Exception {
    BufferedReader question = new BufferedReader(new FileReader(qaArgs.config + "/a.toks"));
    BufferedReader answer = new BufferedReader(new FileReader(qaArgs.config + "/b.toks"));
    BufferedReader idFile = new BufferedReader(new FileReader(qaArgs.config + "/id.txt"));
    FileWriter outFile = new FileWriter(qaArgs.config + ".eval");

    int count = 0;
    while (true) {
      String partOne = question.readLine();
      String partTwo = answer.readLine();
      String id = idFile.readLine();

      if (partOne == null || partTwo == null || id == null)
        break;

      System.out.println(id + " 0 " + count + " 0 " + scorer.getIDF(partOne, partTwo) + " smmodel");
      outFile.write(id + " 0 " + count + " 0 " + scorer.getIDF(partOne, partTwo) + " smmodel\n");
      count += 1;
    }
  }

  public static void main(String[] args) throws Exception {
    Args qaArgs = new Args();
    CmdLineParser parser = new CmdLineParser(qaArgs, ParserProperties.defaults().withUsageWidth(90));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: RetrieveSentences" + parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }

    if (qaArgs.topics.isEmpty() && qaArgs.query.isEmpty()){
      System.err.println("Pass either a query or a topic. Both can't be empty.");
      return;
    }

    RetrieveSentences rs = new RetrieveSentences(qaArgs);
//    rs.getRankedPassages(qaArgs);
    rs.getIDFPassages(qaArgs);
  }
}
